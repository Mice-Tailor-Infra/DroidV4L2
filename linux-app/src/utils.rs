use gstreamer as gst;
use gstreamer::prelude::*;
use log::{error, info, warn};
use std::path::Path;
use std::process::Command;
use std::thread;
use std::time::Duration;

pub fn check_plugin(name: &str) {
    let registry = gst::Registry::get();
    match registry.find_feature(name, gst::ElementFactory::static_type()) {
        Some(_) => println!("[CHECK] Plugin '{}' FOUND.", name),
        None => println!(
            "[CHECK] Plugin '{}' NOT FOUND! (Required for feature)",
            name
        ),
    }
}

pub fn check_and_load_v4l2loopback(device_path: &str) {
    if Path::new(device_path).exists() {
        println!("[Check] Device {} exists.", device_path);
        return;
    }

    println!(
        "[Warning] Device {} NOT found. Attempting to load v4l2loopback...",
        device_path
    );
    println!("[System] Requesting sudo privileges via pkexec...");

    let video_nr = device_path.replace("/dev/video", "");

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
                thread::sleep(Duration::from_millis(500));
            } else {
                eprintln!(
                    "[Error] Failed to load v4l2loopback (exit code: {:?})",
                    exit_status.code()
                );
            }
        }
        Err(e) => {
            eprintln!("[Error] Failed to execute pkexec: {}", e);
        }
    }
}

pub fn start_mdns_broadcast() {
    thread::spawn(move || {
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
                let _ = child.wait();
                warn!("[mDNS] avahi-publish exited unexpectedly!");
            }
            Err(e) => {
                error!("[mDNS] Failed to execute avahi-publish: {}", e);
                info!("[mDNS] Is 'avahi-utils' installed? 'sudo apt install avahi-utils'");
            }
        }
    });
}
