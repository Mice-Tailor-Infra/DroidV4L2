use gstreamer as gst;
use gstreamer_app as gst_app;
use std::time::Instant;

pub struct BridgeState {
    pub last_sample: Option<gst::Sample>,
    pub last_update: Instant,
    pub appsrc: gst_app::AppSrc,
    pub timestamp_ns: u64,
    pub active_codec: String,
}

impl BridgeState {
    pub fn new(appsrc: gst_app::AppSrc) -> Self {
        Self {
            last_sample: None,
            last_update: Instant::now(),
            appsrc,
            timestamp_ns: 0,
            active_codec: "none".to_string(),
        }
    }

    pub fn push_sample_to_appsrc(&mut self, sample: gst::Sample) {
        if let Some(buffer) = sample.buffer() {
            let mut new_buffer = gst::Buffer::with_size(buffer.size()).unwrap();
            {
                let b = new_buffer.get_mut().unwrap();
                b.set_pts(gst::ClockTime::from_nseconds(self.timestamp_ns));
                // 30fps -> ~33.33ms
                b.set_duration(gst::ClockTime::from_nseconds(33_333_333));
                let map = buffer.map_readable().unwrap();
                let mut new_map = b.map_writable().unwrap();
                new_map.copy_from_slice(&map);
            }
            let _ = self.appsrc.push_buffer(new_buffer);
            self.timestamp_ns += 33_333_333;
        }
    }
}
