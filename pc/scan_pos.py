"""
POS 창 UI 구조 자동 스캔 → 결과 파일 저장
탭이 이미지인 경우: 각 탭 클릭 후 변화 감지
"""
from pywinauto import Application, Desktop, findwindows
import time

OUTPUT = "scan_result.txt"
lines = []

def log(msg):
    print(msg)
    lines.append(msg)

def save():
    with open(OUTPUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"\n=== 결과 저장: {OUTPUT} ===")

def get_all_texts(win):
    texts = set()
    try:
        for child in win.descendants():
            try:
                t = child.window_text()
                if t and t.strip():
                    texts.add(t.strip())
            except Exception:
                continue
    except Exception:
        pass
    return texts

log("=" * 60)
log("  POS 창 UI 스캔 (탭 자동 클릭 포함)")
log(f"  시간: {time.strftime('%Y-%m-%d %H:%M:%S')}")
log("=" * 60)

# 1. MATE POS 팝업 자동 닫기
log("\n[1] MATE POS 팝업 확인...")
try:
    mate_app = Application(backend="uia").connect(title="MATE POS", timeout=3)
    mate_win = mate_app.window(title="MATE POS")
    mate_texts = [c.window_text() for c in mate_win.descendants() if c.window_text()]
    if "실행 중입니다" in " ".join(mate_texts):
        log("  팝업 발견 → 자동 닫기")
        try:
            mate_win.child_window(title="확인").click_input()
            time.sleep(1)
        except Exception:
            pass
except Exception:
    log("  팝업 없음")

# 2. 메인 창 연결
log("\n[2] 메인 창 연결...")
target_win = None
for kw in ["메인", "GENESIS", "BBQ", "POS"]:
    for backend in ["uia", "win32"]:
        try:
            app = Application(backend=backend).connect(title_re=f".*{kw}.*", timeout=3)
            win = app.window(title_re=f".*{kw}.*")
            title = win.window_text()
            try:
                child_texts = [c.window_text() for c in win.descendants() if c.window_text()]
                if "실행 중입니다" in " ".join(child_texts) and len(child_texts) < 6:
                    continue
            except Exception:
                pass
            target_win = win
            log(f"  연결: \"{title}\" (키워드: {kw}, 백엔드: {backend})")
            break
        except Exception:
            pass
    if target_win:
        break

if not target_win:
    log("[!] POS 창 없음")
    save()
    input("엔터...")
    exit()

# 3. 현재 상태 텍스트
log(f"\n[3] 현재 텍스트:")
before_texts = get_all_texts(target_win)
for t in sorted(before_texts):
    log(f"  [{t}]")

# 4. 빈 텍스트 탭 (이미지 탭) 찾기 + 각각 클릭 후 변화 감지
log(f"\n[4] 이미지 탭 탐색 (빈 텍스트 + 유사 크기 요소):")
log("-" * 60)

tabs = []
try:
    for child in target_win.descendants():
        try:
            text = child.window_text()
            ctrl_type = child.friendly_class_name()
            auto_id = child.automation_id()
            rect = child.rectangle()
            w, h = rect.width(), rect.height()
            # 탭 크기 범위 (80~150 x 30~60)
            if not text.strip() and 80 <= w <= 160 and 30 <= h <= 60:
                tabs.append({
                    "id": auto_id,
                    "type": ctrl_type,
                    "x": rect.left,
                    "y": rect.top,
                    "w": w,
                    "h": h,
                    "element": child,
                })
        except Exception:
            continue
except Exception:
    pass

# y좌표 기준으로 같은 줄 탭 그룹핑
if tabs:
    # 가장 많은 탭이 있는 y좌표 찾기
    y_groups = {}
    for tab in tabs:
        y_key = tab["y"] // 10 * 10  # 10px 단위 그룹핑
        if y_key not in y_groups:
            y_groups[y_key] = []
        y_groups[y_key].append(tab)

    # 가장 큰 그룹 = 탭 줄
    tab_row = max(y_groups.values(), key=len)
    tab_row.sort(key=lambda t: t["x"])

    log(f"\n  탭 {len(tab_row)}개 발견 (y={tab_row[0]['y']}):")
    for i, tab in enumerate(tab_row):
        log(f"    탭{i+1}: id={tab['id']}  pos=({tab['x']},{tab['y']})  size=({tab['w']}x{tab['h']})")

    # 각 탭 클릭 후 변화 감지
    log(f"\n[5] 각 탭 클릭 → 텍스트 변화 감지:")
    log("-" * 60)

    for i, tab in enumerate(tab_row):
        try:
            log(f"\n  --- 탭{i+1} 클릭 (id={tab['id']}, x={tab['x']}) ---")
            tab["element"].click_input()
            time.sleep(1.5)

            after_texts = get_all_texts(target_win)
            new_texts = after_texts - before_texts
            gone_texts = before_texts - after_texts

            if new_texts:
                log(f"  [새로 나타남]:")
                for t in sorted(new_texts):
                    log(f"    + [{t}]")
            if gone_texts:
                log(f"  [사라짐]:")
                for t in sorted(gone_texts):
                    log(f"    - [{t}]")
            if not new_texts and not gone_texts:
                log(f"  (변화 없음)")

            # 전체 텍스트 목록
            log(f"  [현재 텍스트 ({len(after_texts)}개)]:")
            for t in sorted(after_texts):
                log(f"    [{t}]")

            before_texts = after_texts

        except Exception as e:
            log(f"  [ERR] {e}")

else:
    log("  이미지 탭을 찾지 못했습니다.")

    # 모든 요소 상세 덤프
    log(f"\n[5] 전체 요소 덤프:")
    try:
        for child in target_win.descendants():
            try:
                text = child.window_text() or ""
                auto_id = child.automation_id()
                rect = child.rectangle()
                ctrl_type = child.friendly_class_name()
                log(f"  [{ctrl_type}] text=\"{text}\" id=\"{auto_id}\" ({rect.left},{rect.top})({rect.width()}x{rect.height()})")
            except Exception:
                continue
    except Exception:
        pass

save()
input("\n엔터를 누르면 종료...")
