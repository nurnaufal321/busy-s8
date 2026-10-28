//! busy-matter: the BUSY Bar's smart-home switch (services/busy_timer/busy_timer_smart_home.c)
//! as a Matter On/Off Plug-in Unit, so Apple Home, Google Home and Home Assistant can pair with
//! the phone directly. Built on rs-matter; the Android app runs it as a child process.
//!
//! Usage: `busy-matter <data dir> <serial number>`
//!
//! The app and this process talk in lines:
//! - stdin `on` / `off`: the BUSY timer changed the switch (Matter follows, nothing is driven);
//!   `open`: open a pairing window.
//! - stdout `switch on` / `switch off`: a controller set the switch; `pairing <11 digits>`;
//!   `fabrics <n>`: how many ecosystems the device is paired with; `window open|closed`.
//!   The QR payload is in the log (stderr) as `SetupQRCode: [MT:...]`.
//!
//! The process exits when stdin closes, so it never outlives the app.

use core::pin::pin;

use std::io::{BufRead, Write as _};
use std::net::UdpSocket;
use std::path::PathBuf;

use embassy_futures::select::select4;
use embassy_time::{Duration, Timer};

use log::{info, warn};

use rand::{Rng, RngExt as _};

use rs_matter::crypto::{default_crypto, Crypto};
use rs_matter::dm::clusters::app::on_off::{self, NoLevelControl, OnOffHooks, OutOfBandMessage};
use rs_matter::dm::clusters::decl::on_off as on_off_cluster;
use rs_matter::dm::clusters::decl::on_off::Feature;
use rs_matter::dm::clusters::basic_info::BasicInfoConfig;
use rs_matter::dm::clusters::desc::{self, ClusterHandler as _};
use rs_matter::dm::clusters::groups::{self, ClusterHandler as _};
use rs_matter::dm::devices::test::{DAC_PRIVKEY, TEST_DEV_ATT, TEST_PID, TEST_VID};
use rs_matter::dm::endpoints;
use rs_matter::dm::networks::SysNetifs;
use rs_matter::dm::{Async, Cluster, DataModel, Dataver, DeviceType, Endpoint, Node};
use rs_matter::error::Error;
use rs_matter::im::{InteractionModel, InteractionModelState};
use rs_matter::pairing::DiscoveryCapabilities;
use rs_matter::persist::DirKvBlobStore;
use rs_matter::respond::DefaultResponder;
use rs_matter::sc::pase::{
    CommWindowState, Spake2pVerifierPassword, Spake2pVerifierPasswordRef, MAX_COMM_WINDOW_TIMEOUT_SECS,
};
use rs_matter::transport::exchange::MatterBuffers;
use rs_matter::transport::MATTER_SOCKET_BIND_ADDR;
use rs_matter::utils::cell::RefCell;
use rs_matter::utils::select::Coalesce;
use rs_matter::utils::sync::blocking::Mutex;
use rs_matter::{clusters, devices, root_endpoint, with, BasicCommData, Matter, MATTER_PORT};

mod mdns;

/// On/Off Plug-in Unit (Matter Device Library 5.1): a switch that turns something on and off,
/// which is what the bar's smart-home switch is.
const DEV_TYPE_ON_OFF_PLUG_IN_UNIT: DeviceType = DeviceType { dtype: 0x010A, drev: 3 };

/// The switch. Controllers' commands arrive in [`OnOffHooks::set_on_off`] and go to the app;
/// the app's changes come in through [`Self::updates`].
struct BusySwitch {
    on: Mutex<RefCell<bool>>,
    updates: async_channel::Receiver<bool>,
}

impl OnOffHooks for BusySwitch {
    // The plug-in unit requires the Lighting feature on OnOff, like a light.
    const CLUSTER: Cluster<'static> = on_off_cluster::FULL_CLUSTER
        .with_revision(6)
        .with_features(Feature::LIGHTING.bits())
        .with_attrs(with!(
            required;
            on_off_cluster::AttributeId::OnOff
                | on_off_cluster::AttributeId::GlobalSceneControl
                | on_off_cluster::AttributeId::OnTime
                | on_off_cluster::AttributeId::OffWaitTime
                | on_off_cluster::AttributeId::StartUpOnOff
        ))
        .with_cmds(with!(
            on_off_cluster::CommandId::Off
                | on_off_cluster::CommandId::On
                | on_off_cluster::CommandId::Toggle
                | on_off_cluster::CommandId::OffWithEffect
                | on_off_cluster::CommandId::OnWithRecallGlobalScene
                | on_off_cluster::CommandId::OnWithTimedOff
        ));

    // The app owns the real state; persist at once so a restart reports the same value.
    const PERSIST_DELAY_MS: u32 = 0;

    fn set_on_off(&self, on: bool) {
        self.on.lock(|s| *s.borrow_mut() = on);
        say(if on { "switch on" } else { "switch off" });
    }

    async fn handle_off_with_effect(&self, _effect: on_off::EffectVariantEnum) {}

    async fn run<F: Fn(OutOfBandMessage)>(&self, notify: F) {
        loop {
            match self.updates.recv().await {
                Ok(on) => notify(OutOfBandMessage::Update(on)),
                Err(_) => core::future::pending::<()>().await,
            }
        }
    }
}

/// One protocol line to the app.
fn say(line: &str) {
    let mut out = std::io::stdout().lock();
    let _ = writeln!(out, "{line}");
    let _ = out.flush();
}

/// This device's passcode and discriminator, made once and kept in `<data>/pairing`. Never the
/// well-known test passcode: anyone on the network could pair while a window is open.
fn comm_data(dir: &std::path::Path) -> BasicCommData {
    let file = dir.join("pairing");
    let saved = std::fs::read_to_string(&file).ok().and_then(|s| {
        let mut it = s.split_whitespace().map(|v| v.parse::<u32>().ok());
        Some((it.next()??, it.next()?? as u16))
    });
    let (passcode, discriminator) = saved.unwrap_or_else(|| {
        let mut rng = rand::rng();
        // Core spec 5.1.7.1: 1..=99999998, and none of the trivial codes.
        const INVALID: [u32; 12] = [
            0, 11111111, 22222222, 33333333, 44444444, 55555555, 66666666, 77777777, 88888888,
            99999999, 12345678, 87654321,
        ];
        let passcode = loop {
            let p = rng.random_range(1..=99_999_998);
            if !INVALID.contains(&p) {
                break p;
            }
        };
        let discriminator = rng.random_range(0..0x1000u16);
        let _ = std::fs::write(&file, format!("{passcode} {discriminator}\n"));
        (passcode, discriminator)
    });
    BasicCommData {
        password: Spake2pVerifierPassword::new_from_ref(Spake2pVerifierPasswordRef::new(&passcode.to_le_bytes())),
        discriminator,
    }
}

fn main() -> Result<(), Error> {
    env_logger::init_from_env(env_logger::Env::default().filter_or(env_logger::DEFAULT_FILTER_ENV, "info"));

    let mut args = std::env::args().skip(1);
    let dir = PathBuf::from(args.next().unwrap_or_else(|| ".".into()));
    let serial: &'static str = Box::leak(args.next().unwrap_or_else(|| "busy-s8".into()).into_boxed_str());
    std::fs::create_dir_all(&dir).map_err(|_| rs_matter::error::ErrorCode::StdIoError)?;

    // The development attestation certificate (VID 0xFFF1, PID 0x8001), as in rs-matter's
    // examples: controllers treat the device as uncertified, which it is.
    let info: &'static BasicInfoConfig = Box::leak(Box::new(BasicInfoConfig {
        vid: TEST_VID,
        pid: TEST_PID,
        hw_ver: 1,
        hw_ver_str: "1",
        sw_ver: 1,
        sw_ver_str: "0.1.0",
        serial_no: serial,
        unique_id: serial,
        manufacturing_date: "20261028",
        product_name: "BUSY Bar",
        product_label: "BUSY Bar",
        vendor_name: "BUSY S8",
        ..BasicInfoConfig::new()
    }));
    let comm = comm_data(&dir);
    let pairing_code = comm.compute_pairing_code();
    let matter = Matter::new(info, comm, &TEST_DEV_ATT, MATTER_PORT);

    let store = DirKvBlobStore::new(dir.join("kv"));
    let buffers: MatterBuffers = MatterBuffers::new();
    let state: InteractionModelState = InteractionModelState::new();
    let kv = matter.kv(store);
    matter.startup(&kv)?;

    let crypto = default_crypto(rand::rng(), DAC_PRIVKEY);
    let mut rand = crypto.rand()?;

    // stdin: the app's commands, read on a plain thread and handed to the async side.
    let (update_tx, update_rx) = async_channel::unbounded::<bool>();
    let (open_tx, open_rx) = async_channel::unbounded::<()>();
    std::thread::spawn(move || {
        for line in std::io::stdin().lock().lines() {
            match line.as_deref().map(str::trim) {
                Ok("on") => drop(update_tx.send_blocking(true)),
                Ok("off") => drop(update_tx.send_blocking(false)),
                Ok("open") => drop(open_tx.send_blocking(())),
                Ok(other) => warn!("unknown command: {other}"),
                Err(_) => break,
            }
        }
        // The app is gone (or closed our stdin to stop us).
        std::process::exit(0);
    });

    let on_off_handler = on_off::OnOffHandler::<_, NoLevelControl>::new(
        Dataver::new_rand(&mut rand),
        1,
        rs_matter::persist::VENDOR_KEYS_START + 0x10,
        BusySwitch { on: Mutex::new(RefCell::new(false)), updates: update_rx },
    );
    on_off_handler.init(None);

    let im = InteractionModel::new(&matter, &crypto, &buffers, data_model(rand, &on_off_handler), &kv, &state);
    futures_lite::future::block_on(im.startup())?;

    let responder = DefaultResponder::new(&im);
    let mut respond = pin!(responder.run::<4, 4>());
    let mut im_job = pin!(im.run());

    let socket = async_io::Async::<UdpSocket>::bind(MATTER_SOCKET_BIND_ADDR)?;
    let mut mdns = pin!(mdns::run_mdns(&matter, &crypto));
    let mut transport = pin!(matter.run(&crypto, &socket, &socket, &socket));

    say(&format!("pairing {pairing_code}"));
    matter.print_standard_qr_text(DiscoveryCapabilities::IP)?;
    if !matter.has_fabrics() {
        im.open_basic_comm_window(MAX_COMM_WINDOW_TIMEOUT_SECS)?;
    }

    // Pairing windows on request, and the pairing state for the app's screens.
    let mut control = pin!(async {
        let mut last = (usize::MAX, None::<bool>);
        loop {
            if let Ok(Ok(())) = futures_lite::future::or(async { Ok(open_rx.recv().await) }, async {
                Timer::after(Duration::from_secs(2)).await;
                Err(())
            })
            .await
            {
                match im.open_basic_comm_window(MAX_COMM_WINDOW_TIMEOUT_SECS) {
                    Ok(()) => info!("pairing window opened"),
                    Err(e) => warn!("cannot open a pairing window: {e:?}"),
                }
            }
            let fabrics = matter.with_state(|s| s.fabrics.iter().count());
            let open = !matches!(matter.comm_window_state(), CommWindowState::Closed);
            if (fabrics, Some(open)) != last {
                if fabrics != last.0 {
                    say(&format!("fabrics {fabrics}"));
                }
                if Some(open) != last.1 {
                    say(if open { "window open" } else { "window closed" });
                }
                last = (fabrics, Some(open));
            }
        }
        #[allow(unreachable_code)]
        Ok::<(), Error>(())
    });

    let all = select4(
        &mut transport,
        &mut mdns,
        &mut respond,
        select4(&mut im_job, &mut control, core::future::pending::<Result<(), Error>>(), core::future::pending::<Result<(), Error>>()).coalesce(),
    )
    .coalesce();
    futures_lite::future::block_on(all)
}

const NODE: Node<'static> = Node {
    endpoints: &[
        root_endpoint!(eth),
        Endpoint::new(
            1,
            devices!(DEV_TYPE_ON_OFF_PLUG_IN_UNIT),
            clusters!(desc::DescHandler::CLUSTER, groups::GroupsHandler::CLUSTER, BusySwitch::CLUSTER),
        ),
    ],
};

fn data_model<'a, OH: OnOffHooks>(
    mut rand: impl Rng + Copy,
    on_off: &'a on_off::OnOffHandler<'a, OH, NoLevelControl>,
) -> impl DataModel + 'a {
    (
        NODE,
        endpoints::EthSysHandlerBuilder::new()
            .netif_diag(&SysNetifs)
            .build(rand)
            .chain(
                |e, c| e == 1 && c == desc::DescHandler::CLUSTER.id,
                Async(desc::DescHandler::new(Dataver::new_rand(&mut rand)).adapt()),
            )
            .chain(
                |e, c| e == 1 && c == groups::GroupsHandler::CLUSTER.id,
                Async(groups::GroupsHandler::new(Dataver::new_rand(&mut rand)).adapt()),
            )
            .chain(|e, c| e == 1 && c == BusySwitch::CLUSTER.id, Async(on_off::HandlerAdaptor(on_off))),
    )
}
