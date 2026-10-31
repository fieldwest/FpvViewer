# Raspberry Pi FPV Sender Setup (Pi3B+)

このドキュメントは、Raspberry Pi 3B+ を FPVView（Android）向けの **FPV送信機** として構築するための手順をまとめたものです。  
NetworkManager による AP 化、MAC ベースの SSID 自動生成、DHCP 検知による自動ストリーミング開始までを自動化します。
Raspberry Pi 3B+ のOSは、Raspberry Pi OS Liteの64-bitです。
---

## 📦 1. GStreamer（送信コマンド）インストール

```bash
sudo apt update
sudo apt install -y \
  gstreamer1.0-tools \
  gstreamer1.0-plugins-base \
  gstreamer1.0-plugins-good \
  gstreamer1.0-plugins-bad \
  gstreamer1.0-plugins-ugly \
  gstreamer1.0-libav
```

---

## 📡 2. AP 化（NetworkManager）

```bash
sudo nmcli connection add type wifi ifname wlan0 con-name FPV-AP autoconnect yes ssid fpv-car-temp
sudo nmcli connection modify FPV-AP 802-11-wireless.mode ap 802-11-wireless.band bg
sudo nmcli connection modify FPV-AP ipv4.method shared ipv4.addresses 192.168.4.1/24
sudo nmcli connection modify FPV-AP wifi-sec.key-mgmt wpa-psk
sudo nmcli connection modify FPV-AP wifi-sec.psk "camera123"
```

---

## 🔧 3. AP起動およびSSID自動設定

### AP起動＆SSID生成スクリプト
```bash
sudo nano /usr/local/bin/fpv-ap-ssid.sh
```

### 内容：
```bash
#!/bin/bash

# MAC 下4桁でSSID生成
MAC=$(cat /sys/class/net/wlan0/address)
SUFFIX=${MAC:12:5}
SUFFIX=${SUFFIX//:/}
SSID="FPV-AP-$SUFFIX"

# 重ならない 1 / 6 / 11 からランダム選択
#CHANNELS=(1 6 11)
#CH=${CHANNELS[$RANDOM % ${#CHANNELS[@]}]}
CH=1

logger "FPV-AP: SSID=$SSID CH=$CH"

nmcli connection modify FPV-AP 802-11-wireless.ssid "$SSID" 802-11-wireless.channel "$CH"
nmcli connection down FPV-AP
nmcli connection up FPV-AP
```
```bash
sudo chmod +x /usr/local/bin/fpv-ap-ssid.sh
```

### systemd サービス
```bash
sudo nano /etc/systemd/system/fpv-ap-ssid.service
```

### 内容：
```service
[Unit]
Description=Set FPV AP SSID based on MAC
After=NetworkManager.service

[Service]
Type=oneshot
ExecStart=/usr/local/bin/fpv-ap-ssid.sh

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable fpv-ap-ssid.service
```
---

## 📡 4. DHCP 監視（スマホ接続をトリガーにストリーム再起動）
### inotify-tools インストール
```bash
sudo apt install inotify-tools
```

### 監視スクリプト
```bash
sudo nano /usr/local/bin/fpv-watch.sh
````

### 内容：
```bash
#!/bin/bash

LEASE=/var/lib/NetworkManager/dnsmasq-wlan0.leases

inotifywait -m -e modify "$LEASE" | while read; do
    IP=$(awk '{print $3}' "$LEASE")
    logger "FPV DHCP DETECTED: $IP"
    systemctl restart fpv-stream.service
done
```
```bash
sudo chmod +x /usr/local/bin/fpv-watch.sh
```

### systemd サービス
```bash
sudo nano /etc/systemd/system/fpv-watch.service
```
### 内容：
```service
[Unit]
Description=FPV DHCP Watcher

[Service]
ExecStart=/usr/local/bin/fpv-watch.sh

[Install]
WantedBy=multi-user.target
```
```bash
sudo systemctl enable --now fpv-watch.service
```
---

## 🎥 5. 配信用スクリプト & systemd サービス

### 配信用スクリプト
```bash
sudo nano /usr/local/bin/fpv-stream.sh
```
```bash
#!/bin/bash

LEASE=/var/lib/NetworkManager/dnsmasq-wlan0.leases

if [ ! -s "$LEASE" ]; then
    logger "FPV-STREAM: lease file empty, abort"
    exit 1
fi

IP=$(awk '{print $3}' "$LEASE" | tail -n1)

if [ -z "$IP" ]; then
    logger "FPV-STREAM: no IP found in lease, abort"
    exit 1
fi

logger "FPV-STREAM: streaming to $IP"

#rpicam-vid -n -t 0 --width 320 --height 240 --framerate 60 --intra 8 --inline --bitrate 1500000 -o - | \
#    gst-launch-1.0 fdsrc ! h264parse config-interval=1 ! video/x-h264,stream-format=byte-stream ! udpsink host="$IP" port=5000 sync=false
rpicam-vid -n -t 0 --width 160 --height 120 --framerate 60 --intra 8 --inline --bitrate 1500000 -o - | \
    gst-launch-1.0 fdsrc ! h264parse config-interval=1 ! video/x-h264,stream-format=byte-stream ! udpsink host="$IP" port=5000 sync=false
```

```bash
sudo chmod +x /usr/local/bin/fpv-stream.sh
```
### systemd サービス
```bash
sudo nano /etc/systemd/system/fpv-stream.service
```
```service
[Unit]
Description=FPV Camera Streaming
After=network.target
StartLimitIntervalSec=0

[Service]
Type=simple
ExecStart=/usr/local/bin/fpv-stream.sh
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable fpv-stream.service
```
---

## その他（メンテナンス用インターネットWiFi接続）
必要があれば、Pi3B+のメンテナンス用にインターネットWiFi接続必要です

### インターネットWiFi接続
```bash
sudo nmcli device wifi connect "（SSID）" password "（パスワード）"
```

### インターネット接続切替シェル
```bash
sudo nano ~/wifi.sh
```
### 内容：
```bash
sudo nmcli connection down FPV-AP
sudo nmcli connection up （SSID）
```

```bash
sudo chmod 777 wifi.sh
```
---

## OS最新化
```bash
sudo apt update
sudo apt full-upgrade -y
sudo reboot
```
