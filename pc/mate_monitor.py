"""
GENESIS BBQ POS 주문 건수 모니터 → GitHub Gist로 공유
PosDelay 폰 앱에서 읽어서 광고 자동 제어에 활용

사용법:
  1. Python 설치 (python.org → Add to PATH 체크)
  2. cmd에서: pip install pywinauto requests pillow pytesseract
  3. Tesseract OCR 설치: https://github.com/UB-Mannheim/tesseract/wiki
     → 설치 시 "Additional language data" 에서 Korean 체크
  4. 이 파일 실행: python mate_monitor.py

처음 실행 시 설정 자동 안내됩니다.
"""

import ctypes
import json
import os
import re
import time
import requests

CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")

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
    print("\n=== 첫 실행: 설정 파일 생성 ===\n")
    print("GitHub Personal Access Token이 필요합니다.")
    print("  1. https://github.com/settings/tokens/new 접속")
    print("  2. Note: PosDelay2")
    print("  3. Expiration: No expiration")
    print("  4. 체크: gist")
    print("  5. Generate token 클릭 → 토큰 복사\n")
    token = input("토큰 붙여넣기: ").strip()
    cfg = DEFAULT_CONFIG.copy()
    cfg["github_token"] = token
    save_config(cfg)
    print(f"\n[OK] config.json 생성 완료: {CONFIG_FILE}")
    return cfg


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
            print("[OK] MATE POS 팝업 자동 닫기")
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
    from pywinauto import Application, findwindows
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
            print(f"[OK] POS 연결: {win.window_text()} ({backend})")
            return app, win
        except Exception:
            pass

    print(f"\n[!] '{keyword}' 창을 찾을 수 없습니다.")
    print("현재 열린 창:")
    try:
        for w in findwindows.find_elements():
            if w.name.strip():
                print(f"  - {w.name}")
    except Exception:
        pass
    return None, None


def is_mouse_active():
    """마우스 사용 중인지 감지 (0.3초간 커서 이동 확인)"""
    try:
        class POINT(ctypes.Structure):
            _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]
        pt1 = POINT()
        ctypes.windll.user32.GetCursorPos(ctypes.byref(pt1))
        time.sleep(0.3)
        pt2 = POINT()
        ctypes.windll.user32.GetCursorPos(ctypes.byref(pt2))
        return pt1.x != pt2.x or pt1.y != pt2.y
    except Exception:
        return False


def click_delivery_tab(win, tab_id):
    """자동화 ID로 배달 탭 클릭 (마우스 사용 중이면 건너뜀)"""
    if is_mouse_active():
        return False
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
        # OCR (한국어 + 영어)
        text = pytesseract.image_to_string(img, lang="kor+eng")
        return text
    except Exception as e:
        print(f"[!] OCR 오류: {e}")
        return ""


def extract_order_count_ocr(ocr_text):
    """OCR 텍스트에서 건수 추출"""
    lines = ocr_text.replace("\n", " ")

    # "처리중 3", "처리중3" 패턴
    m = re.search(r"(?:처리중|진행중|조리중|접수대기|접수|대기)[\s:]*(\d+)", lines)
    if m:
        return int(m.group(1)), m.group(0).strip()

    # "배달 3", "배달3"
    m = re.search(r"배달[\s]*(\d+)", lines)
    if m:
        return int(m.group(1)), m.group(0).strip()

    # "전체 3"
    m = re.search(r"전체[\s]*(\d+)", lines)
    if m:
        return int(m.group(1)), m.group(0).strip()

    # "N건"
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
    # 1차: pywinauto 텍스트
    count, matched = extract_order_count_text(win)
    if count is not None:
        return count, f"텍스트: {matched}"

    # 2차: OCR
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
        print(f"[!] 네트워크 오류: {e}")
        return False


def main():
    print("=" * 50)
    print("  GENESIS BBQ POS 주문 모니터")
    print("=" * 50)

    cfg = load_config()

    if not cfg["github_token"]:
        print("\n[!] GitHub 토큰이 없습니다.")
        print("  1. https://github.com/settings/tokens/new → gist 체크\n")
        token = input("토큰 붙여넣기: ").strip()
        if token:
            cfg["github_token"] = token
            save_config(cfg)
        else:
            print("[!] 토큰 필요"); return

    # MATE POS 팝업 닫기
    dismiss_popup()

    # POS 연결
    app, win = connect_pos(cfg)
    if not win:
        input("\n엔터를 누르면 종료...")
        return

    # OCR 사용 가능 여부 확인
    use_ocr = False
    try:
        import pytesseract
        from PIL import Image
        tesseract_path = cfg.get("tesseract_path", "")
        if tesseract_path and os.path.exists(tesseract_path):
            pytesseract.pytesseract.tesseract_cmd = tesseract_path
        pytesseract.get_tesseract_version()
        use_ocr = True
        print("[OK] OCR 사용 가능 (Tesseract)")
    except Exception:
        print("[!] OCR 미설치 → 텍스트 모드만 사용")
        print("    OCR 설치: pip install pytesseract pillow")
        print("    Tesseract: https://github.com/UB-Mannheim/tesseract/wiki")

    # 배달 탭 클릭
    tab_id = cfg["delivery_tab_id"]
    print(f"\n배달 탭 클릭 (id={tab_id})...")
    if click_delivery_tab(win, tab_id):
        print("[OK] 배달 탭 클릭 성공")
    else:
        print("[!] 배달 탭 클릭 실패")
        print("    scan_pos.py로 정확한 ID를 확인하세요.")

    time.sleep(1)

    # 초기 건수 확인
    count, matched = read_order_count(win, cfg, use_ocr)
    if count is not None:
        print(f"[OK] 주문 건수: {count}건 ({matched})")
    else:
        print("[!] 건수 감지 실패")
        if use_ocr:
            ocr_text = capture_and_ocr(win, cfg)
            print(f"OCR 결과 (처음 500자):\n{ocr_text[:500]}")

    # 모니터링 루프
    interval = cfg["poll_interval_sec"]
    print(f"\n{interval}초 간격 모니터링 시작... (Ctrl+C 종료)\n")

    last_count = -1
    fail_count = 0
    while True:
        try:
            # 창 재연결
            try:
                win.window_text()
            except Exception:
                print(f"[{time.strftime('%H:%M:%S')}] 창 재연결...")
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
                    print(f"[{time.strftime('%H:%M:%S')}] 주문: {count}건 ({last_count}→{count}) [{matched}]")
                    if update_gist(cfg, count):
                        print(f"[{time.strftime('%H:%M:%S')}] Gist 업데이트 완료")
                    last_count = count
            else:
                fail_count += 1
                if fail_count % 10 == 1:
                    print(f"[{time.strftime('%H:%M:%S')}] 건수 감지 실패 ({fail_count}회)")

            time.sleep(interval)

        except KeyboardInterrupt:
            print("\n모니터링 종료")
            break
        except Exception as e:
            print(f"[!] 오류: {e}")
            time.sleep(interval)


if __name__ == "__main__":
    main()
