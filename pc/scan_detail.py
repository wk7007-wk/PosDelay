"""
배달 탭 클릭 후 모든 UI 요소 상세 덤프
→ 처리중/건수 등 숨겨진 요소의 auto_id 찾기
"""
from pywinauto import Application
import time

OUTPUT = "scan_detail.txt"
lines = []

def log(msg):
    print(msg)
    lines.append(msg)

def save():
    with open(OUTPUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"\n=== 결과 저장: {OUTPUT} ===")

log("=" * 60)
log("  배달 탭 내부 요소 상세 스캔")
log(f"  시간: {time.strftime('%Y-%m-%d %H:%M:%S')}")
log("=" * 60)

# 1. MATE POS 팝업 닫기
try:
    app = Application(backend="uia").connect(title="MATE POS", timeout=3)
    win = app.window(title="MATE POS")
    texts = [c.window_text() for c in win.descendants() if c.window_text()]
    if "실행 중입니다" in " ".join(texts):
        log("[OK] 팝업 닫기")
        win.child_window(title="확인").click_input()
        time.sleep(1)
except Exception:
    pass

# 2. 메인 창 연결
log("\n[1] 메인 창 연결...")
app = Application(backend="uia").connect(title_re=".*메인.*", timeout=5)
win = app.window(title_re=".*메인.*")
log(f"  연결: \"{win.window_text()}\"")

# 3. 배달 탭 클릭 (id=198354)
TAB_ID = "198354"
log(f"\n[2] 배달 탭 클릭 (id={TAB_ID})...")
try:
    tab = win.child_window(auto_id=TAB_ID)
    if tab.exists(timeout=3):
        tab.click_input()
        log("  [OK] 클릭 성공")
    else:
        log("  [!] 탭 없음")
except Exception as e:
    log(f"  [!] 클릭 실패: {e}")

time.sleep(2)

# 4. 모든 요소 덤프 (빈 텍스트 포함)
log(f"\n[3] 전체 요소 덤프 (빈 텍스트 포함):")
log("-" * 60)

count = 0
try:
    for child in win.descendants():
        try:
            text = child.window_text() or ""
            auto_id = child.automation_id() or ""
            ctrl_type = child.friendly_class_name()
            rect = child.rectangle()
            x, y = rect.left, rect.top
            w, h = rect.width(), rect.height()

            # 너무 작은 요소 건너뛰기
            if w < 5 or h < 5:
                continue

            count += 1
            text_display = text.replace("\n", " ")[:50]
            log(f"  [{count}] type={ctrl_type}  id=\"{auto_id}\"  text=\"{text_display}\"  pos=({x},{y})  size=({w}x{h})")
        except Exception:
            continue
except Exception as e:
    log(f"  [ERR] {e}")

log(f"\n  총 {count}개 요소")

# 5. 특별히 숫자가 포함된 요소 찾기
log(f"\n[4] 숫자 포함 요소:")
log("-" * 60)
try:
    for child in win.descendants():
        try:
            text = child.window_text() or ""
            if any(c.isdigit() for c in text):
                auto_id = child.automation_id() or ""
                ctrl_type = child.friendly_class_name()
                rect = child.rectangle()
                log(f"  type={ctrl_type}  id=\"{auto_id}\"  text=\"{text}\"  pos=({rect.left},{rect.top})  size=({rect.width()}x{rect.height()})")
        except Exception:
            continue
except Exception:
    pass

# 6. 이미지 탭과 비슷한 크기의 빈 텍스트 요소 (서브탭 후보)
log(f"\n[5] 서브탭 후보 (빈 텍스트, 크기 40~200 x 20~60):")
log("-" * 60)
try:
    for child in win.descendants():
        try:
            text = child.window_text() or ""
            auto_id = child.automation_id() or ""
            rect = child.rectangle()
            w, h = rect.width(), rect.height()
            if not text.strip() and 40 <= w <= 200 and 20 <= h <= 60 and auto_id:
                ctrl_type = child.friendly_class_name()
                log(f"  id=\"{auto_id}\"  type={ctrl_type}  pos=({rect.left},{rect.top})  size=({w}x{h})")
        except Exception:
            continue
except Exception:
    pass

save()
input("\n엔터를 누르면 종료...")
