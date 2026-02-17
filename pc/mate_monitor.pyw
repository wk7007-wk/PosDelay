"""
메이트포스 주문 건수 모니터 → GitHub Gist로 공유
PosDelay 폰 앱에서 읽어서 광고 자동 제어에 활용

백그라운드 실행 (.pyw → CMD 창 없음)
로그: pc/monitor_log.txt 에 기록

사용법:
  1. Python 설치 (python.org → Add to PATH 체크)
  2. cmd에서: pip install pywinauto requests pillow pytesseract
  3. Tesseract OCR 설치: https://github.com/UB-Mannheim/tesseract/wiki
     → 설치 시 "Additional language data" 에서 Korean 체크
  4. mate_monitor.py를 먼저 한번 실행 (config.json 생성)
  5. 더블클릭으로 실행: mate_monitor.pyw (창 없이 백그라운드)
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

# 로그 설정
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
    "window_title": "메인",
    "delivery_tab_id": "198354",
    "poll_interval_sec": 30,
    "tesseract_path": r"C:\Program Files\Tesseract-OCR\tesseract.exe",
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


def dismiss_popup():
    """MATE POS 팝업 자동 닫기"""
    from pywinauto import Application
    try:
        app = Application(backend="uia").connect(title="MATE POS", timeout=3)
        win = app.window(title="MATE POS")
        texts = [c.window_text() for c in win.descendants() if c.window_text()]
        if "실행 중입니다" in " ".join(texts):
            log.info("MATE POS 팝업 자동 닫기")
            try:
                btn = win.child_window(title="확인")
                try:
                    btn.invoke()
                except Exception:
                    try:
                        btn.click()
                    except Exception:
                        btn.click_input()
                time.sleep(1)
            except Exception:
                pass
    except Exception:
        pass


def connect_pos(cfg):
    """POS 메인 창에 연결"""
    from pywinauto import Application
    keyword = cfg["window_title"]

    for backend in ["uia", "win32"]:
        try:
            app = Application(backend=backend).connect(title_re=f".*{keyword}.*", timeout=5)
            win = app.window(title_re=f".*{keyword}.*")
            # 팝업 아닌지 확인
            try:
                child_texts = [c.window_text() for c in win.descendants() if c.window_text()]
                if "실행 중입니다" in " ".join(child_texts) and len(child_texts) < 6:
                    continue
            except Exception:
                pass
            log.info(f"[OK] POS 연결: {win.window_text()} ({backend})")
            return app, win
        except Exception:
            pass

    log.warning(f"[!] '{keyword}' 창 없음")
    return None, None


def click_delivery_tab(win, tab_id):
    """자동화 ID로 배달 탭 클릭 (마우스 이동 없이)"""
    try:
        tab = win.child_window(auto_id=tab_id)
        if tab.exists(timeout=2):
            try:
                tab.invoke()
                return True
            except Exception:
                pass
            try:
                tab.click()
                return True
            except Exception:
                pass
            tab.click_input()
            return True
    except Exception:
        pass
    return False


def capture_and_ocr(win, cfg):
    """창 스크린샷 → OCR로 텍스트 추출"""
    import pytesseract
    from PIL import Image

    tesseract_path = cfg.get("tesseract_path", "")
    if tesseract_path and os.path.exists(tesseract_path):
        pytesseract.pytesseract.tesseract_cmd = tesseract_path

    try:
        img = win.capture_as_image()
        text = pytesseract.image_to_string(img, lang="kor+eng")
        return text
    except Exception as e:
        log.warning(f"[!] OCR 오류: {e}")
        return ""


def extract_order_count_ocr(ocr_text):
    """OCR 텍스트에서 건수 추출"""
    lines = ocr_text.replace("\n", " ")

    m = re.search(r"(?:처리중|진행중|조리중|접수대기|접수|대기)[\s:]*(\d+)", lines)
    if m:
        return int(m.group(1)), m.group(0).strip()

    m = re.search(r"배달[\s]*(\d+)", lines)
    if m:
        return int(m.group(1)), m.group(0).strip()

    m = re.search(r"전체[\s]*(\d+)", lines)
    if m:
        return int(m.group(1)), m.group(0).strip()

    m = re.search(r"(\d+)\s*건", lines)
    if m:
        return int(m.group(1)), m.group(0).strip()

    return None, None


def extract_order_count_text(win):
    """pywinauto 텍스트에서 건수 추출 (OCR 없이)"""
    texts = []
    try:
        for child in win.descendants():
            try:
                t = child.window_text()
                if t and t.strip():
                    texts.append(t.strip())
            except Exception:
                continue
    except Exception:
        pass

    for text in texts:
        m = re.search(r"배달[\s]*(\d+)", text)
        if m:
            return int(m.group(1)), text
        m = re.search(r"(?:처리중|진행중|조리중|접수대기)[\s:]*(\d+)", text)
        if m:
            return int(m.group(1)), text
        m = re.search(r"(\d+)\s*건", text)
        if m:
            return int(m.group(1)), text
    return None, None


def read_order_count(win, cfg, use_ocr=True):
    """건수 읽기 (텍스트 → OCR 순서)"""
    count, matched = extract_order_count_text(win)
    if count is not None:
        return count, f"텍스트: {matched}"

    if use_ocr:
        ocr_text = capture_and_ocr(win, cfg)
        if ocr_text:
            count, matched = extract_order_count_ocr(ocr_text)
            if count is not None:
                return count, f"OCR: {matched}"

    return None, None


def update_gist(cfg, count):
    """GitHub Gist에 주문 건수 업데이트"""
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

    # MATE POS 팝업 닫기
    dismiss_popup()

    # OCR 사용 가능 여부 확인
    use_ocr = False
    try:
        import pytesseract
        tesseract_path = cfg.get("tesseract_path", "")
        if tesseract_path and os.path.exists(tesseract_path):
            pytesseract.pytesseract.tesseract_cmd = tesseract_path
        pytesseract.get_tesseract_version()
        use_ocr = True
        log.info("[OK] OCR 사용 가능 (Tesseract)")
    except Exception:
        log.warning("[!] OCR 미설치 → 텍스트 모드만 사용")

    # POS 연결 대기 (최대 5분)
    app, win = None, None
    for attempt in range(10):
        dismiss_popup()
        app, win = connect_pos(cfg)
        if win:
            break
        log.info(f"[{attempt+1}/10] POS 대기 중... (30초 후 재시도)")
        time.sleep(30)

    if not win:
        log.error("[!] POS 연결 실패. 종료.")
        return

    tab_id = cfg["delivery_tab_id"]
    interval = cfg["poll_interval_sec"]
    log.info(f"모니터링 시작 ({interval}초 간격, 탭 ID={tab_id})")

    last_count = -1
    fail_count = 0
    while True:
        try:
            # 창 재연결
            try:
                win.window_text()
            except Exception:
                log.info("창 재연결 시도...")
                dismiss_popup()
                app, win = connect_pos(cfg)
                if not win:
                    time.sleep(interval)
                    continue

            # 배달 탭 클릭
            click_delivery_tab(win, tab_id)
            time.sleep(0.5)

            # 건수 읽기
            count, matched = read_order_count(win, cfg, use_ocr)

            if count is not None:
                fail_count = 0
                if count != last_count:
                    log.info(f"주문: {count}건 ({last_count}→{count}) [{matched}]")
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
