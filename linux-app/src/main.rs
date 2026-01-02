use anyhow::{Result, Context};
use clap::Parser;
use gstreamer as gst;
use gstreamer_app as gst_app;
use gst::prelude::*;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use std::thread;
use std::io::Write;

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
    let args = Args::parse();
    gst::init().context("GStreamer init failed")?;

    println!("[SYSTEM] GStreamer initialized.");
    check_plugin("h264parse");
    check_plugin("avdec_h264");
    check_plugin("h265parse");
    check_plugin("avdec_h265");
    std::io::stdout().flush().unwrap();

    // --- 1. 持久 Sink 管线 (Caps Lockdown) ---
    // 关键点：appsrc 的 caps 必须被死死锁定，不允许有任何模糊。
    // 我们强制要求上游必须送来 I420 1080p 30fps。
    let sink_pipeline_str = format!(
        "appsrc name=mysrc is-live=true format=time min-latency=0 caps=\"video/x-raw,format=I420,width=1920,height=1080,framerate=30/1,pixel-aspect-ratio=1/1\" ! videoconvert ! videoscale ! video/x-raw,format=YUY2,width=1920,height=1080 ! v4l2sink device={} sync=false",
        args.device
    );
    let sink_pipeline = gst::parse_launch(&sink_pipeline_str)?;
    let appsrc = sink_pipeline
        .downcast_ref::<gst::Bin>().unwrap()
        .by_name("mysrc").unwrap()
        .downcast::<gst_app::AppSrc>().unwrap();

    sink_pipeline.set_state(gst::State::Playing)?;
    println!("[SYSTEM] Permanent Sink Active on {} (Caps Locked)", args.device);

    let state = Arc::new(Mutex::new(BridgeState {
        last_sample: None,
        last_update: Instant::now(),
        appsrc: appsrc.clone(),
        timestamp_ns: 0,
        active_codec: "none".to_string(),
    }));

    // --- 2. Watchdog 线程 (保活与清理) ---
    let state_watchdog = Arc::clone(&state);
    thread::spawn(move || {
        loop {
            thread::sleep(Duration::from_millis(33));
            let mut s = state_watchdog.lock().unwrap();
            let elapsed = s.last_update.elapsed();
            
            if elapsed > Duration::from_millis(500) {
                if s.last_sample.is_some() {
                    println!("[WATCHDOG] Stream idle for 500ms. Clearing buffer.");
                    s.last_sample = None;
                    s.active_codec = "none".to_string();
                }
            } else if elapsed > Duration::from_millis(200) {
                if let Some(sample) = s.last_sample.clone() {
                    if s.active_codec != "none" {
                        push_sample_to_appsrc(&mut s, sample, true);
                    }
                }
            }
        }
    });

    // --- 3. 启动双路监听 ---
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

        // 关键改动：无论解码出来是什么鬼样子，都强制 Scale + Convert 成 I420 1080p
        // 这确保了 appsink 收到的数据与 appsrc 要求的格式完美匹配。
        let src_pipeline_str = format!(
            "srtsrc uri=srt://:{}?mode=listener&latency=20&streamid=live&poll-timeout=100 ! tsdemux ! {} max-threads=4 ! videoconvert ! videoscale ! video/x-raw,format=I420,width=1920,height=1080 ! appsink name=mysink sync=false drop=true max-buffers=1",
            port, parser_decoder
        );

        match gst::parse_launch(&src_pipeline_str) {
            Ok(src_pipe) => {
                println!("[NETWORK] {} Listener OPEN on port {}", codec, port);
                let appsink = src_pipe.downcast_ref::<gst::Bin>().unwrap()
                    .by_name("mysink").unwrap()
                    .downcast::<gst_app::AppSink>().unwrap();

                let state_input = Arc::clone(&state);
                let codec_name = codec.to_string();
                
                appsink.set_callbacks(
                    gst_app::AppSinkCallbacks::builder()
                        .new_sample(move |sink| {
                            let sample = sink.pull_sample().map_err(|_| gst::FlowError::Error)?;
                            let mut s = state_input.lock().unwrap();
                            
                            if s.active_codec != codec_name {
                                println!("[SWITCH] Codec changed: {} -> {}", s.active_codec, codec_name);
                                s.active_codec = codec_name.clone();
                            }
                            
                            s.last_sample = Some(sample.clone());
                            s.last_update = Instant::now();
                            push_sample_to_appsrc(&mut s, sample, false);
                            Ok(gst::FlowSuccess::Ok)
                        })
                        .build()
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
    // 禁术：Caps Lockdown
    // 我们不再调用 s.appsrc.set_caps()。因为我们假设上游已经把格式洗得很干净了。
    // 这骗过了下游，让它以为格式从未改变。
    
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