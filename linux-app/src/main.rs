use anyhow::{Result, Context};
use clap::Parser;
use gstreamer as gst;
use gst::prelude::*;
use std::time::Duration;
use std::thread;

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(short, long, default_value_t = 5000)]
    port: u16,
    #[arg(short, long, default_value = "/dev/video10")]
    device: String,
}

fn run_pipeline(args: &Args) -> Result<()> {
    // 强制每次重新创建，确保资源释放干净
    let pipeline_str = format!(
        "srtsrc uri=srt://:{}?mode=listener&latency=20&streamid=live&poll-timeout=1000 ! \
         tsdemux ! h264parse ! avdec_h264 ! \
         queue max-size-buffers=1 leaky=downstream ! \
         videoconvert ! video/x-raw,format=YUY2 ! \
         v4l2sink device={} sync=false",
        args.port, args.device
    );

    println!("Building new pipeline...");
    let pipeline = gst::parse_launch(&pipeline_str).context("Failed to parse pipeline")?;
    
    pipeline.set_state(gst::State::Playing).context("Failed to set Playing state")?;
    println!("Bridge listening on port {} (streamid=live)...", args.port);

    let bus = pipeline.bus().expect("Pipeline without bus");
    
    for msg in bus.iter_timed(gst::ClockTime::NONE) {
        use gst::MessageView;
        match msg.view() {
            MessageView::Error(err) => {
                let src_name = msg.src().map(|s| s.path_string()).unwrap_or_else(|| "unknown".into());
                println!("Pipeline Error (from {}): {}. Resetting...", src_name, err.error());
                break; 
            }
            MessageView::Eos(..) => {
                println!("Source disconnected (EOS). Resetting...");
                break;
            }
            MessageView::StateChanged(s) => {
                if let Some(src) = msg.src() {
                    if src.has_pipeline(&pipeline) && s.current() == gst::State::Playing {
                        println!("--- SRT DATA STREAM ACTIVE ---");
                    }
                }
            }
            _ => (),
        }
    }

    println!("Cleaning up pipeline...");
    pipeline.set_state(gst::State::Null)?;
    Ok(())
}

fn main() -> Result<()> {
    let args = Args::parse();
    gst::init().context("Failed to initialize GStreamer")?;

    println!("DroidV4L2 Linux Bridge Started.");
    
    loop {
        if let Err(e) = run_pipeline(&args) {
            eprintln!("Pipeline execution failed: {}. Restarting in 1s...", e);
            thread::sleep(Duration::from_secs(1));
        }
        // 给系统一点点缓冲时间来完全释放端口
        thread::sleep(Duration::from_millis(200));
    }
}

trait GstObjExt {
    fn has_pipeline(&self, pipeline: &gst::Element) -> bool;
}

impl GstObjExt for gst::Object {
    fn has_pipeline(&self, pipeline: &gst::Element) -> bool {
        self.downcast_ref::<gst::Element>().map(|e| e == pipeline).unwrap_or(false)
    }
}