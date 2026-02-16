"""
메이트포스 주문 건수 모니터 → GitHub Gist로 공유
PosDelay 폰 앱에서 읽어서 광고 자동 제어에 활용

사용법:
  1. Python 설치 (python.org → Add to PATH 체크)
  2. cmd에서: pip install pywinauto requests
  3. 이 파일 실행: python mate_monitor.py

처음 실행 시 설정 자동 안내됩니다.
"""

import json
import os
import re
import time
import requests

CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")

DEFAULT_CONFIG = {
    "github_token": "",
    "gist_id": "a67e5de3271d6d0716b276dc6a8391cb",
    "window_title": "genesis",
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
    print("\n=== 첫 실행: 설정 파일 생성 ===\n")
    print("GitHub Personal Access Token이 필요합니다.")
    print("  1. https://github.com/settings/tokens/new 접속")
    print("  2. Note: PosDelay")
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


def connect_mate_pos(cfg):
    """메이트포스 창에 연결"""
    from pywinauto import Application, findwindows

    keyword = cfg["window_title"]

    try:
        # UIA 백엔드 (최신 UI 프레임워크 지원)
        app = Application(backend="uia").connect(title_re=f".*{keyword}.*", timeout=5)
        win = app.window(title_re=f".*{keyword}.*")
        print(f"[OK] 메이트포스 연결: {win.window_text()}")
        return app, win
    except Exception:
        pass

    try:
        # Win32 백엔드 (기존 Win32 앱)
        app = Application(backend="win32").connect(title_re=f".*{keyword}.*", timeout=5)
        win = app.window(title_re=f".*{keyword}.*")
        print(f"[OK] 메이트포스 연결 (win32): {win.window_text()}")
        return app, win
    except Exception:
        pass

    # 창 목록 표시
    print(f"\n[!] '{keyword}' 포함 창을 찾을 수 없습니다.")
    print("\n현재 열린 창:")
    try:
        windows = findwindows.find_elements()
        for w in windows:
            if w.name.strip():
                print(f"  - {w.name}")
    except Exception:
        pass
    print(f"\nconfig.json의 window_title을 수정하세요.")
    return None, None


def click_delivery_tab(win, tab_text):
    """텍스트로 배달 탭 찾아서 클릭"""
    try:
        # 방법 1: 정확한 텍스트 매칭
        btn = win.child_window(title=tab_text, found_index=0)
        if btn.exists(timeout=2):
            btn.click_input()
            return True
    except Exception:
        pass

    try:
        # 방법 2: 부분 텍스트 매칭
        btn = win.child_window(title_re=f".*{tab_text}.*", found_index=0)
        if btn.exists(timeout=2):
            btn.click_input()
            return True
    except Exception:
        pass

    try:
        # 방법 3: 모든 자식 요소에서 텍스트 검색
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
    """창 내 모든 텍스트 수집"""
    texts = []
    try:
        # 창 제목
        texts.append(win.window_text())
    except Exception:
        pass

    try:
        # 모든 자식 요소 텍스트
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
    """텍스트 목록에서 처리중 건수 추출"""
    for text in texts:
        # "처리중3", "처리중 3건", "처리중: 3", "처리중(3)" 등
        m = re.search(r"(?:처리중|진행중|접수|대기)[\s:：()（）]*(\d+)", text)
        if m:
            return int(m.group(1)), text
        m = re.search(r"(\d+)\s*건", text)
        if m:
            return int(m.group(1)), text
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
    print("  메이트포스 주문 모니터 (PosDelay PC)")
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

    # 메이트포스 연결
    app, win = connect_mate_pos(cfg)
    if not win:
        input("\n엔터를 누르면 종료...")
        return

    # 배달 탭 클릭 테스트
    tab_text = cfg["delivery_tab_text"]
    print(f"\n'{tab_text}' 탭 검색 중...")
    if click_delivery_tab(win, tab_text):
        print(f"[OK] '{tab_text}' 탭 클릭 성공")
    else:
        print(f"[!] '{tab_text}' 탭을 찾지 못했습니다.")
        print("    config.json의 delivery_tab_text를 수정하세요.")
        # 모든 텍스트 표시 (디버깅)
        texts = read_all_texts(win)
        print(f"\n창 텍스트 ({len(texts)}개):")
        for t in texts[:30]:
            print(f"  [{t}]")

    time.sleep(1)

    # 초기 건수 확인
    texts = read_all_texts(win)
    count, matched = extract_order_count(texts)
    if count is not None:
        print(f"[OK] 주문 건수: {count}건 (매칭: {matched})")
    else:
        print("[!] 건수 감지 실패. 텍스트 확인:")
        for t in texts[:20]:
            print(f"  [{t}]")

    # 모니터링 루프
    interval = cfg["poll_interval_sec"]
    print(f"\n{interval}초 간격 모니터링 시작... (Ctrl+C 종료)\n")

    last_count = -1
    fail_count = 0
    while True:
        try:
            # 창 재연결 (최소화/재시작 대응)
            try:
                win.window_text()
            except Exception:
                print(f"[{time.strftime('%H:%M:%S')}] 창 재연결 시도...")
                app, win = connect_mate_pos(cfg)
                if not win:
                    time.sleep(interval)
                    continue

            # 배달 탭 클릭
            click_delivery_tab(win, tab_text)
            time.sleep(0.5)

            # 텍스트 읽기
            texts = read_all_texts(win)
            count, matched = extract_order_count(texts)

            if count is not None:
                fail_count = 0
                if count != last_count:
                    print(f"[{time.strftime('%H:%M:%S')}] 주문: {count}건 ({last_count}→{count})")
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
