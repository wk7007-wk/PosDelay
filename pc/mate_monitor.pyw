"""
메이트포스 주문 건수 모니터 → GitHub Gist로 공유
PosDelay 폰 앱에서 읽어서 광고 자동 제어에 활용

백그라운드 실행 (.pyw → CMD 창 없음)
로그: pc/monitor_log.txt 에 기록

사용법:
  1. Python 설치 (python.org → Add to PATH 체크)
  2. cmd에서: pip install pywinauto requests
  3. 더블클릭으로 실행: mate_monitor.pyw (창 없이 백그라운드)
  4. 또는 CMD에서: pythonw mate_monitor.pyw
"""

import json
import logging
import os
import re
import sys
import time
import requests

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILE = os.path.join(SCRIPT_DIR, "config.json")
LOG_FILE = os.path.join(SCRIPT_DIR, "monitor_log.txt")

# 로그 설정 (파일 + 최대 1MB 로테이션)
logging.basicConfig(
    filename=LOG_FILE,
    level=logging.INFO,
    format="%(asctime)s %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    encoding="utf-8",
)
log = logging.getLogger("mate_monitor")

DEFAULT_CONFIG = {
    "github_token": "",
    "gist_id": "a67e5de3271d6d0716b276dc6a8391cb",
    "window_title": "GENESIS",
    "delivery_tab_text": "배달",
    "poll_interval_sec": 30,
}


def load_config():
    if os.path.exists(CONFIG_FILE):
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            cfg = json.load(f)
            for k, v in DEFAULT_CONFIG.items():
                if k not in cfg:
                    cfg[k] = v
            return cfg
    return None


def save_config(cfg):
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)


def connect_pos(cfg):
    from pywinauto import Application
    keyword = cfg["window_title"]
    try:
        app = Application(backend="uia").connect(title_re=f".*{keyword}.*", timeout=5)
        win = app.window(title_re=f".*{keyword}.*")
        log.info(f"[OK] 창 연결: {win.window_text()}")
        return app, win
    except Exception:
        pass
    try:
        app = Application(backend="win32").connect(title_re=f".*{keyword}.*", timeout=5)
        win = app.window(title_re=f".*{keyword}.*")
        log.info(f"[OK] 창 연결 (win32): {win.window_text()}")
        return app, win
    except Exception:
        pass
    log.warning(f"[!] '{keyword}' 창 없음")
    return None, None


def click_delivery_tab(win, tab_text):
    try:
        btn = win.child_window(title=tab_text, found_index=0)
        if btn.exists(timeout=2):
            btn.click_input()
            return True
    except Exception:
        pass
    try:
        btn = win.child_window(title_re=f".*{tab_text}.*", found_index=0)
        if btn.exists(timeout=2):
            btn.click_input()
            return True
    except Exception:
        pass
    try:
        for child in win.descendants():
            try:
                name = child.window_text()
                if tab_text in name:
                    child.click_input()
                    return True
            except Exception:
                continue
    except Exception:
        pass
    return False


def read_all_texts(win):
    texts = []
    try:
        texts.append(win.window_text())
    except Exception:
        pass
    try:
        for child in win.descendants():
            try:
                text = child.window_text()
                if text and text.strip():
                    texts.append(text.strip())
            except Exception:
                continue
    except Exception:
        pass
    return texts


def extract_order_count(texts):
    for text in texts:
        m = re.search(r"배달[\s]*(\d+)", text)
        if m:
            return int(m.group(1)), text
        m = re.search(r"(?:처리중|진행중|조리중|접수대기|접수|대기)[\s:：()（）]*(\d+)", text)
        if m:
            return int(m.group(1)), text
        m = re.search(r"전체[\s]*(\d+)", text)
        if m:
            return int(m.group(1)), text
        m = re.search(r"(\d+)\s*건", text)
        if m:
            return int(m.group(1)), text
    return None, None


def update_gist(cfg, count):
    token = cfg["github_token"]
    if not token:
        return False
    url = f"https://api.github.com/gists/{cfg['gist_id']}"
    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3+json",
    }
    data = {
        "files": {
            "order_status.json": {
                "content": json.dumps({
                    "count": count,
                    "time": time.strftime("%Y-%m-%d %H:%M:%S"),
                    "source": "pc",
                }, ensure_ascii=False)
            }
        }
    }
    try:
        resp = requests.patch(url, headers=headers, json=data, timeout=10)
        return resp.status_code == 200
    except Exception as e:
        log.warning(f"[!] 네트워크 오류: {e}")
        return False


def register_startup():
    """Windows 시작프로그램에 등록"""
    try:
        import winreg
        key_path = r"Software\Microsoft\Windows\CurrentVersion\Run"
        pyw_path = os.path.join(SCRIPT_DIR, "mate_monitor.pyw")
        pythonw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
        if not os.path.exists(pythonw):
            pythonw = "pythonw"
        cmd = f'"{pythonw}" "{pyw_path}"'

        key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_SET_VALUE)
        winreg.SetValueEx(key, "PosDelayMonitor", 0, winreg.REG_SZ, cmd)
        winreg.CloseKey(key)
        log.info(f"[OK] 시작프로그램 등록: {cmd}")
        return True
    except Exception as e:
        log.warning(f"[!] 시작프로그램 등록 실패: {e}")
        return False


def main():
    log.info("=" * 40)
    log.info("  PosDelay PC 모니터 시작 (백그라운드)")
    log.info("=" * 40)

    cfg = load_config()
    if not cfg or not cfg.get("github_token"):
        log.error("[!] config.json 없음 또는 토큰 미설정. mate_monitor.py를 먼저 실행하세요.")
        return

    # 시작프로그램 등록 (최초 1회)
    register_startup()

    # POS 연결 대기 (최대 5분)
    app, win = None, None
    for attempt in range(10):
        app, win = connect_pos(cfg)
        if win:
            break
        log.info(f"[{attempt+1}/10] POS 대기 중... (30초 후 재시도)")
        time.sleep(30)

    if not win:
        log.error("[!] POS 연결 실패. 종료.")
        return

    tab_text = cfg["delivery_tab_text"]
    interval = cfg["poll_interval_sec"]
    log.info(f"모니터링 시작 ({interval}초 간격)")

    last_count = -1
    fail_count = 0
    while True:
        try:
            # 창 재연결
            try:
                win.window_text()
            except Exception:
                log.info("창 재연결 시도...")
                app, win = connect_pos(cfg)
                if not win:
                    time.sleep(interval)
                    continue

            click_delivery_tab(win, tab_text)
            time.sleep(0.5)

            texts = read_all_texts(win)
            count, matched = extract_order_count(texts)

            if count is not None:
                fail_count = 0
                if count != last_count:
                    log.info(f"주문: {count}건 ({last_count}→{count})")
                    if update_gist(cfg, count):
                        log.info("Gist 업데이트 완료")
                    last_count = count
            else:
                fail_count += 1
                if fail_count % 10 == 1:
                    log.warning(f"건수 감지 실패 ({fail_count}회)")

            time.sleep(interval)

        except KeyboardInterrupt:
            log.info("모니터링 종료")
            break
        except Exception as e:
            log.error(f"오류: {e}")
            time.sleep(interval)


if __name__ == "__main__":
    main()
