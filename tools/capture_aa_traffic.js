/**
 * Frida script to capture Android Auto protocol traffic from the real Google AA app.
 * 
 * Usage:
 *   1. Connect phone to head unit via USB
 *   2. Start the real Android Auto app (com.google.android.projection.gearhead)
 *   3. Run: frida -U -n "com.google.android.projection.gearhead" -l tools/capture_aa_traffic.js
 *   
 * This hooks FileOutputStream.write() and FileInputStream.read() on the USB accessory
 * file descriptor to capture all protocol traffic.
 *
 * Requires: rooted phone with Frida server running
 */

'use strict';

// Track which file descriptors are USB accessory FDs
var usbFds = {};

// Hook UsbAccessory.openAccessory to capture the FD
Java.perform(function() {
    console.log("[*] Android Auto Protocol Capture");
    console.log("[*] Hooking USB I/O...");

    // Hook UsbManager.openAccessory to get the ParcelFileDescriptor
    var UsbManager = Java.use("android.hardware.usb.UsbManager");
    UsbManager.openAccessory.implementation = function(accessory) {
        var pfd = this.openAccessory(accessory);
        if (pfd != null) {
            var fd = pfd.getFd();
            usbFds[fd] = true;
            console.log("[*] USB Accessory opened: fd=" + fd);
        }
        return pfd;
    };

    // Hook FileOutputStream.write(byte[], int, int) for USB writes
    var FileOutputStream = Java.use("java.io.FileOutputStream");
    FileOutputStream.write.overload('[B', 'int', 'int').implementation = function(buf, off, len) {
        var fd = this.getFD();
        // Log all writes (we can't easily check FD number from Java)
        if (len > 0 && len < 100000) {
            var bytes = Java.array('byte', buf);
            var hex = "";
            var maxLog = Math.min(len, 64);
            for (var i = off; i < off + maxLog; i++) {
                hex += ("0" + (bytes[i] & 0xff).toString(16)).slice(-2) + " ";
            }
            var channel = bytes[off] & 0xff;
            var flags = bytes[off + 1] & 0xff;
            var frameType = flags & 0x03;
            var isControl = (flags & 0x04) != 0;
            var isEncrypted = (flags & 0x08) != 0;
            
            console.log("[TX] len=" + len + " ch=" + channel + " flags=0x" + flags.toString(16) + 
                " (type=" + frameType + " ctrl=" + isControl + " enc=" + isEncrypted + ")");
            console.log("     " + hex + (len > maxLog ? "..." : ""));
            
            // If not encrypted, try to parse message type
            if (!isEncrypted && len > 6) {
                var msgType = ((bytes[off + 4] & 0xff) << 8) | (bytes[off + 5] & 0xff);
                console.log("     msgType=0x" + msgType.toString(16));
            }
        }
        return this.write(buf, off, len);
    };

    // Hook FileInputStream.read(byte[], int, int) for USB reads
    var FileInputStream = Java.use("java.io.FileInputStream");
    FileInputStream.read.overload('[B', 'int', 'int').implementation = function(buf, off, len) {
        var result = this.read(buf, off, len);
        if (result > 0 && result < 100000) {
            var bytes = Java.array('byte', buf);
            var hex = "";
            var maxLog = Math.min(result, 64);
            for (var i = off; i < off + maxLog; i++) {
                hex += ("0" + (bytes[i] & 0xff).toString(16)).slice(-2) + " ";
            }
            var channel = bytes[off] & 0xff;
            var flags = bytes[off + 1] & 0xff;
            var frameType = flags & 0x03;
            var isControl = (flags & 0x04) != 0;
            var isEncrypted = (flags & 0x08) != 0;
            
            console.log("[RX] len=" + result + " ch=" + channel + " flags=0x" + flags.toString(16) +
                " (type=" + frameType + " ctrl=" + isControl + " enc=" + isEncrypted + ")");
            console.log("     " + hex + (result > maxLog ? "..." : ""));
            
            if (!isEncrypted && result > 6) {
                var msgType = ((bytes[off + 4] & 0xff) << 8) | (bytes[off + 5] & 0xff);
                console.log("     msgType=0x" + msgType.toString(16));
            }
        }
        return result;
    };

    // Also hook SSL_write to see plaintext before encryption
    // This gives us the actual protocol messages
    try {
        var SSLEngine = Java.use("javax.net.ssl.SSLEngine");
        console.log("[*] SSLEngine class found");
    } catch(e) {
        console.log("[!] SSLEngine not found: " + e);
    }

    // Hook the wrap() method of SSLEngine to see plaintext being encrypted
    try {
        var SSLEngineResult = Java.use("javax.net.ssl.SSLEngineResult");
        var ByteBuffer = Java.use("java.nio.ByteBuffer");
        var SSLEngine = Java.use("javax.net.ssl.SSLEngine");
        
        SSLEngine.wrap.overload('java.nio.ByteBuffer', 'java.nio.ByteBuffer').implementation = function(src, dst) {
            var pos = src.position();
            var lim = src.limit();
            var remaining = lim - pos;
            
            if (remaining > 0 && remaining < 50000) {
                var arr = Java.array('byte', new Array(Math.min(remaining, 32)));
                var srcDup = src.duplicate();
                srcDup.get(arr);
                var hex = "";
                for (var i = 0; i < arr.length; i++) {
                    hex += ("0" + (arr[i] & 0xff).toString(16)).slice(-2) + " ";
                }
                
                // Parse message type from plaintext
                if (remaining >= 2) {
                    var msgType = ((arr[0] & 0xff) << 8) | (arr[1] & 0xff);
                    console.log("[WRAP] plaintext=" + remaining + " bytes, msgType=0x" + msgType.toString(16));
                    console.log("       " + hex + (remaining > 32 ? "..." : ""));
                }
            }
            
            return this.wrap(src, dst);
        };
        console.log("[*] SSLEngine.wrap hooked");
    } catch(e) {
        console.log("[!] Failed to hook SSLEngine.wrap: " + e);
    }

    // Hook unwrap to see decrypted incoming messages
    try {
        var SSLEngine = Java.use("javax.net.ssl.SSLEngine");
        SSLEngine.unwrap.overload('java.nio.ByteBuffer', 'java.nio.ByteBuffer').implementation = function(src, dst) {
            var result = this.unwrap(src, dst);
            
            var produced = result.bytesProduced();
            if (produced > 0 && produced < 50000) {
                var pos = dst.position() - produced;
                var dstDup = dst.duplicate();
                dstDup.position(pos);
                var arr = Java.array('byte', new Array(Math.min(produced, 32)));
                dstDup.get(arr);
                var hex = "";
                for (var i = 0; i < arr.length; i++) {
                    hex += ("0" + (arr[i] & 0xff).toString(16)).slice(-2) + " ";
                }
                
                if (produced >= 2) {
                    var msgType = ((arr[0] & 0xff) << 8) | (arr[1] & 0xff);
                    console.log("[UNWRAP] plaintext=" + produced + " bytes, msgType=0x" + msgType.toString(16));
                    console.log("         " + hex + (produced > 32 ? "..." : ""));
                }
            }
            
            return result;
        };
        console.log("[*] SSLEngine.unwrap hooked");
    } catch(e) {
        console.log("[!] Failed to hook SSLEngine.unwrap: " + e);
    }

    console.log("[*] Hooks installed. Connect to head unit to capture traffic.");
});
