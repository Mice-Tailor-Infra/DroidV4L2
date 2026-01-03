use anyhow::{Context, Result};
use clap::Parser;
use gst::prelude::*;
use gstreamer as gst;
use gstreamer_app as gst_app;
use log::{error, info, warn};

use std::io::Write;
use std::path::Path;
use std::process::Command;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(short = '4', long, default_value_t = 5000)]
    port_h264: u16,
    #[arg(short = '5', long, default_value_t = 5001)]
    port_h265: u16,
    #[arg(short, long, default_value = "/dev/video10")]
    device: String,
}

struct BridgeState {
    last_sample: Option<gst::Sample>,
    last_update: Instant,
    appsrc: gst_app::AppSrc,
    timestamp_ns: u64,
    active_codec: String,
}

fn main() -> Result<()> {
    // 0. Auto-Modprobe check
    // The original check_and_load_v4l2loopback function does not return Result,
    // so we need to adjust the call site to pass &args.device and handle its internal logic.
    // Or, modify check_and_load_v4l2loopback to return Result.
    // Given the instruction, I will assume the user intends to modify check_and_load_v4l2loopback
    // to return Result<()> or handle the error within. For now, I'll keep the original call signature
    // and adapt the new code to it, as the instruction only modifies the main function's body.
    // Reverting to original call for check_and_load_v4l2loopback based on the provided snippet's context.

    // 1. Parse Arguments
    let args = Args::parse();

    // 0. Auto-Modprobe (Phase 4) - Moved after args parsing
    check_and_load_v4l2loopback(&args.device);

    // 2. Initialize Logging
    if std::env::var("RUST_LOG").is_err() {
        std::env::set_var("RUST_LOG", "info");
    }
    env_logger::init();

    // 3. Start mDNS Service (via avahi-publish)
    // We prefer avahi-publish on Linux to avoid conflicts with the system avahi-daemon
    // which usually holds port 5353.
    std::thread::spawn(move || {
        info!("[mDNS] Attempting to register service using avahi-publish...");

        let output = Command::new("avahi-publish")
            .arg("-s")
            .arg("DroidV4L2 Bridge")
            .arg("_droidv4l2._tcp")
            .arg("5000")
            .spawn();

        match output {
            Ok(mut child) => {
                info!(
                    "[mDNS] avahi-publish started successfully (PID: {})",
                    child.id()
                );
                // Wait for it to exit (it shouldn't, unless error)
                let _ = child.wait();
                warn!("[mDNS] avahi-publish exited unexpectedly!");
            }
            Err(e) => {
                error!("[mDNS] Failed to execute avahi-publish: {}", e);
                info!("[mDNS] Is 'avahi-utils' installed? 'sudo apt install avahi-utils'");
            }
        }
    });

    gst::init().context("GStreamer init failed")?;

    info!("[SYSTEM] GStreamer initialized.");
    check_plugin("h264parse");
    check_plugin("avdec_h264");
    check_plugin("h265parse");
    check_plugin("avdec_h265");
    check_plugin("videotestsrc"); // 检查屏保插件
    std::io::stdout().flush().unwrap();

    // --- 1. 持久 Sink 管线 (Caps Lockdown) ---
    let sink_pipeline_str = format!(
        "appsrc name=mysrc is-live=true format=time min-latency=0 caps=\"video/x-raw,format=I420,width=1920,height=1080,framerate=30/1,pixel-aspect-ratio=1/1\" ! videoconvert ! videoscale ! video/x-raw,format=YUY2,width=1920,height=1080 ! v4l2sink device={} sync=false",
        args.device
    );
    let sink_pipeline = gst::parse_launch(&sink_pipeline_str)?;
    let appsrc = sink_pipeline
        .downcast_ref::<gst::Bin>()
        .unwrap()
        .by_name("mysrc")
        .unwrap()
        .downcast::<gst_app::AppSrc>()
        .unwrap();

    sink_pipeline.set_state(gst::State::Playing)?;
    println!(
        "[SYSTEM] Permanent Sink Active on {} (Caps Locked)",
        args.device
    );

    // --- 2. 屏保发生器 (Screensaver Generator) ---
    // 产生标准的 SMPTE 彩条，格式完全匹配 Locked Caps
    let saver_pipeline_str = "videotestsrc pattern=smpte ! videoscale ! video/x-raw,format=I420,width=1920,height=1080 ! appsink name=saver sync=false drop=true max-buffers=1";
    let saver_pipeline = gst::parse_launch(saver_pipeline_str)?;
    let saver_appsink = saver_pipeline
        .downcast_ref::<gst::Bin>()
        .unwrap()
        .by_name("saver")
        .unwrap()
        .downcast::<gst_app::AppSink>()
        .unwrap();

    saver_pipeline.set_state(gst::State::Playing)?;
    println!("[SYSTEM] Screensaver Generator Active (SMPTE Bars)");

    let state = Arc::new(Mutex::new(BridgeState {
        last_sample: None,
        last_update: Instant::now(),
        appsrc: appsrc.clone(),
        timestamp_ns: 0,
        active_codec: "none".to_string(),
    }));

    // --- 3. Watchdog 线程 (保活、清理、屏保) ---
    let state_watchdog = Arc::clone(&state);
    thread::spawn(move || {
        loop {
            // 30fps = 33ms
            thread::sleep(Duration::from_millis(33));
            let mut s = state_watchdog.lock().unwrap();
            let elapsed = s.last_update.elapsed();

            if elapsed > Duration::from_millis(500) {
                // 状态：IDLE (无连接)
                // 动作：清理残留状态 + 播放屏保
                if s.last_sample.is_some() {
                    // println!("[WATCHDOG] Stream lost. Switching to screensaver.");
                    s.last_sample = None;
                    s.active_codec = "none".to_string();
                }

                // 从屏保管线拉取一帧
                match saver_appsink.pull_sample() {
                    Ok(sample) => {
                        // 推送屏保帧到主管线，保持 V4L2 存活
                        push_sample_to_appsrc(&mut s, sample, true);
                    }
                    Err(_) => {
                        // 屏保管线如果挂了，我们也无能为力，但这通常不可能发生
                    }
                }
            } else if elapsed > Duration::from_millis(200) {
                // 状态：JITTER (网络抖动)
                // 动作：补发上一帧 (Packet Loss Concealment)
                if let Some(sample) = s.last_sample.clone() {
                    if s.active_codec != "none" {
                        push_sample_to_appsrc(&mut s, sample, true);
                    }
                }
            }
        }
    });

    // --- 4. 启动双路监听 ---
    let state_h264 = Arc::clone(&state);
    let port_h264 = args.port_h264;
    let h264_thread = thread::spawn(move || {
        run_source_loop(port_h264, "h264", state_h264);
    });

    let state_h265 = Arc::clone(&state);
    let port_h265 = args.port_h265;
    let h265_thread = thread::spawn(move || {
        run_source_loop(port_h265, "h265", state_h265);
    });

    h264_thread.join().unwrap();
    h265_thread.join().unwrap();

    Ok(())
}

fn check_plugin(name: &str) {
    let registry = gst::Registry::get();
    match registry.find_feature(name, gst::ElementFactory::static_type()) {
        Some(_) => println!("[CHECK] Plugin '{}' FOUND.", name),
        None => println!("[CHECK] Plugin '{}' NOT FOUND! (H.265 might fail)", name),
    }
}

fn run_source_loop(port: u16, codec: &str, state: Arc<Mutex<BridgeState>>) {
    println!("[THREAD] Starting {} loop on port {}", codec, port);
    loop {
        let parser_decoder = if codec == "h264" {
            "h264parse ! avdec_h264"
        } else {
            "h265parse ! avdec_h265"
        };

        // 强制 Scale + Convert 成 I420 1080p
        let src_pipeline_str = format!(
            "srtsrc uri=srt://:{}?mode=listener&latency=20&streamid=live&poll-timeout=100 ! tsdemux ! {} max-threads=4 ! videoconvert ! videoscale ! video/x-raw,format=I420,width=1920,height=1080 ! appsink name=mysink sync=false drop=true max-buffers=1",
            port, parser_decoder
        );

        match gst::parse_launch(&src_pipeline_str) {
            Ok(src_pipe) => {
                println!("[NETWORK] {} Listener OPEN on port {}", codec, port);
                let appsink = src_pipe
                    .downcast_ref::<gst::Bin>()
                    .unwrap()
                    .by_name("mysink")
                    .unwrap()
                    .downcast::<gst_app::AppSink>()
                    .unwrap();

                let state_input = Arc::clone(&state);
                let codec_name = codec.to_string();

                appsink.set_callbacks(
                    gst_app::AppSinkCallbacks::builder()
                        .new_sample(move |sink| {
                            let sample = sink.pull_sample().map_err(|_| gst::FlowError::Error)?;
                            let mut s = state_input.lock().unwrap();

                            if s.active_codec != codec_name {
                                println!(
                                    "[SWITCH] Codec changed: {} -> {}",
                                    s.active_codec, codec_name
                                );
                                s.active_codec = codec_name.clone();
                            }

                            s.last_sample = Some(sample.clone());
                            s.last_update = Instant::now();
                            push_sample_to_appsrc(&mut s, sample, false);
                            Ok(gst::FlowSuccess::Ok)
                        })
                        .build(),
                );

                if let Err(e) = src_pipe.set_state(gst::State::Playing) {
                    println!("[GST-ERR] {} set_state(Playing) failed: {}", codec, e);
                }

                let bus = src_pipe.bus().unwrap();
                for msg in bus.iter_timed(gst::ClockTime::NONE) {
                    use gst::MessageView;
                    match msg.view() {
                        MessageView::Error(_) | MessageView::Eos(..) => break,
                        _ => (),
                    }
                }
                src_pipe.set_state(gst::State::Null).unwrap();
                println!("[NETWORK] {} Listener CLOSED (restarting...)", codec);
            }
            Err(e) => {
                println!("[GST-ERR] Failed to launch {} pipeline: {}", codec, e);
                thread::sleep(Duration::from_secs(1));
            }
        }
        thread::sleep(Duration::from_millis(10));
    }
}

fn push_sample_to_appsrc(s: &mut BridgeState, sample: gst::Sample, _is_keepalive: bool) {
    // Caps Lockdown: 绝不调用 set_caps
    if let Some(buffer) = sample.buffer() {
        let mut new_buffer = gst::Buffer::with_size(buffer.size()).unwrap();
        {
            let b = new_buffer.get_mut().unwrap();
            b.set_pts(gst::ClockTime::from_nseconds(s.timestamp_ns));
            b.set_duration(gst::ClockTime::from_nseconds(33_333_333));
            let map = buffer.map_readable().unwrap();
            let mut new_map = b.map_writable().unwrap();
            new_map.copy_from_slice(&map);
        }
        let _ = s.appsrc.push_buffer(new_buffer);
        s.timestamp_ns += 33_333_333;
    }
}

fn check_and_load_v4l2loopback(device_path: &str) {
    if Path::new(device_path).exists() {
        println!("[Check] Device {} exists.", device_path);
        return;
    }

    println!(
        "[Warning] Device {} NOT found. Attempting to load v4l2loopback...",
        device_path
    );
    println!("[System] Requesting sudo privileges via pkexec...");

    // Extract video_nr from /dev/video10 -> 10
    let video_nr = device_path.replace("/dev/video", "");

    // Command: pkexec modprobe v4l2loopback video_nr=10 card_label="DroidV4L2" exclusive_caps=1
    let status = Command::new("pkexec")
        .arg("modprobe")
        .arg("v4l2loopback")
        .arg(format!("video_nr={}", video_nr))
        .arg("card_label=DroidV4L2")
        .arg("exclusive_caps=1")
        .status();

    match status {
        Ok(exit_status) => {
            if exit_status.success() {
                println!("[System] v4l2loopback loaded successfully.");
                // Give udev a moment to create the device node
                thread::sleep(Duration::from_millis(500));
            } else {
                eprintln!(
                    "[Error] Failed to load v4l2loopback (exit code: {:?})",
                    exit_status.code()
                );
                // Don't panic here, let GStreamer fail typically if device is still missing
            }
        }
        Err(e) => {
            eprintln!("[Error] Failed to execute pkexec: {}", e);
        }
    }
}
