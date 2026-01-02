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
    active_codec: String, // 记录当前活跃的编码格式
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

    // --- 1. 持久 Sink 管线 ---
    // 强制输出 1080p
    let sink_pipeline_str = format!(
        "appsrc name=mysrc is-live=true format=time min-latency=0 ! videoconvert ! videoscale ! video/x-raw,format=YUY2,width=1920,height=1080 ! v4l2sink device={} sync=false",
        args.device
    );
    let sink_pipeline = gst::parse_launch(&sink_pipeline_str)?;
    let appsrc = sink_pipeline
        .downcast_ref::<gst::Bin>().unwrap()
        .by_name("mysrc").unwrap()
        .downcast::<gst_app::AppSrc>().unwrap();

    sink_pipeline.set_state(gst::State::Playing)?;
    println!("[SYSTEM] Permanent Sink Active on {}", args.device);

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
            
            // 策略：
            // < 200ms: 正常接收，无需补帧
            // 200ms - 500ms: 网络抖动，补发上一帧 (保活)
            // > 500ms: 认为连接已断开，停止补发，清除 last_sample。
            //          这一步至关重要！它确保了当我们切换 Codec 时，Watchdog 不会继续
            //          向管线灌入旧 Codec 的脏数据，从而允许新 Codec 顺利接管。
            
            if elapsed > Duration::from_millis(500) {
                if s.last_sample.is_some() {
                    println!("[WATCHDOG] Stream idle for 500ms. Clearing buffer to allow codec switch.");
                    s.last_sample = None; // 清除缓存，停止发送
                    s.active_codec = "none".to_string();
                }
            } else if elapsed > Duration::from_millis(200) {
                if let Some(sample) = s.last_sample.clone() {
                    // 只有在 active_codec 有效时才补发
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
        // 显式构建管线
        let parser_decoder = if codec == "h264" {
            "h264parse ! avdec_h264"
        } else {
            "h265parse ! avdec_h265"
        };

        // 确保输出 I420，最大限度保证兼容性
        let src_pipeline_str = format!(
            "srtsrc uri=srt://:{}?mode=listener&latency=20&streamid=live&poll-timeout=100 ! tsdemux ! {} max-threads=4 ! videoconvert ! video/x-raw,format=I420 ! appsink name=mysink sync=false drop=true max-buffers=1",
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
                            
                            // 检测 Codec 切换
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

fn push_sample_to_appsrc(s: &mut BridgeState, sample: gst::Sample, is_keepalive: bool) {
    if let Some(caps) = sample.caps() {
        // 可以在这里加日志打印 Caps 变化
        if !is_keepalive {
            // println!("[CAPS] Incoming: {}", caps);
        }
        s.appsrc.set_caps(Some(&caps.to_owned()));
    }
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