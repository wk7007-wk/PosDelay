"""
OCR 진단: 스크린샷 저장 + OCR 원본 텍스트 출력
"""
from pywinauto import Application
import pytesseract
from PIL import Image
import os
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUTPUT = os.path.join(SCRIPT_DIR, "ocr_result.txt")
lines = []

def log(msg):
    print(msg)
    lines.append(msg)

def save():
    with open(OUTPUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"\n=== 결과 저장: {OUTPUT} ===")

# Tesseract 경로
TESS_PATH = r"C:\Program Files\Tesseract-OCR\tesseract.exe"
if os.path.exists(TESS_PATH):
    pytesseract.pytesseract.tesseract_cmd = TESS_PATH
    log(f"[OK] Tesseract: {TESS_PATH}")
else:
    log(f"[!] Tesseract 없음: {TESS_PATH}")

try:
    ver = pytesseract.get_tesseract_version()
    log(f"[OK] Tesseract 버전: {ver}")
except Exception as e:
    log(f"[!] Tesseract 오류: {e}")

# 팝업 닫기
try:
    app = Application(backend="uia").connect(title="MATE POS", timeout=3)
    win = app.window(title="MATE POS")
    texts = [c.window_text() for c in win.descendants() if c.window_text()]
    if "실행 중입니다" in " ".join(texts):
        win.child_window(title="확인").click_input()
        time.sleep(1)
except Exception:
    pass

# 메인 창 연결
app = Application(backend="uia").connect(title_re=".*메인.*", timeout=5)
win = app.window(title_re=".*메인.*")
log(f"[OK] 창: \"{win.window_text()}\"")

# 배달 탭 클릭
tab = win.child_window(auto_id="198354")
if tab.exists(timeout=3):
    tab.click_input()
    log("[OK] 배달 탭 클릭")
time.sleep(2)

# 1. 전체 창 스크린샷 + OCR
log("\n[1] 전체 창 OCR:")
log("-" * 60)
img = win.capture_as_image()
img.save(os.path.join(SCRIPT_DIR, "screenshot_full.png"))
log(f"  스크린샷 저장: screenshot_full.png ({img.size[0]}x{img.size[1]})")

ocr_full = pytesseract.image_to_string(img, lang="kor+eng")
log(f"  OCR 결과:\n{ocr_full}")

# 2. 서브탭 영역만 크롭 + OCR (y=260 부근, 126x34 크기 4개)
log("\n[2] 서브탭 영역 OCR:")
log("-" * 60)
win_rect = win.rectangle()
ox, oy = win_rect.left, win_rect.top

subtab_ids = ["264920", "133094", "133092", "133090"]
for sid in subtab_ids:
    try:
        elem = win.child_window(auto_id=sid)
        rect = elem.rectangle()
        # 창 기준 상대좌표로 크롭
        x1 = rect.left - ox
        y1 = rect.top - oy
        x2 = rect.right - ox
        y2 = rect.bottom - oy
        cropped = img.crop((x1, y1, x2, y2))
        fname = f"subtab_{sid}.png"
        cropped.save(os.path.join(SCRIPT_DIR, fname))
        ocr_text = pytesseract.image_to_string(cropped, lang="kor+eng").strip()
        log(f"  id={sid}  pos=({rect.left},{rect.top})  size=({rect.width()}x{rect.height()})  OCR=\"{ocr_text}\"  img={fname}")
    except Exception as e:
        log(f"  id={sid}  [ERR] {e}")

# 3. 주문목록 영역만 크롭 + OCR
log("\n[3] 주문목록 영역 OCR:")
log("-" * 60)
try:
    list_pane = win.child_window(auto_id="198666")
    rect = list_pane.rectangle()
    x1 = rect.left - ox
    y1 = rect.top - oy
    x2 = rect.right - ox
    y2 = rect.bottom - oy
    cropped = img.crop((x1, y1, x2, y2))
    cropped.save(os.path.join(SCRIPT_DIR, "screenshot_list.png"))
    ocr_text = pytesseract.image_to_string(cropped, lang="kor+eng")
    log(f"  주문목록 OCR:\n{ocr_text}")
except Exception as e:
    log(f"  [ERR] {e}")

save()
input("\n엔터를 누르면 종료...")
