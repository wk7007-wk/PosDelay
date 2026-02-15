package com.posdelay.app.service

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import com.posdelay.app.data.AdActionLog
import com.posdelay.app.data.AdActionLog.Code
import com.posdelay.app.data.AdManager
import java.text.SimpleDateFormat
import java.util.*

class AdWebAutomation(private val activity: Activity) {

    companion object {
        private const val TAG = "AdWebAutomation"

        // 배민: self.baemin.com 진입 → biz-member.baemin.com 로그인 리다이렉트
        private const val BAEMIN_ENTRY_URL = "https://self.baemin.com"

        // 쿠팡이츠: 스토어 포털 로그인
        private const val COUPANG_LOGIN_URL = "https://store.coupangeats.com/merchant/login"

        private const val TIMEOUT_MS = 60000L      // 60초 (SPA 로딩 대기)
        private const val RETRY_DELAY = 2000L      // 재시도 간격 2초
        private const val MAX_RETRIES = 8          // 최대 재시도 (~16초)
    }

    enum class Action {
        BAEMIN_SET_AMOUNT,
        COUPANG_AD_ON,
        COUPANG_AD_OFF
    }

    enum class State {
        IDLE,
        LOADING_LOGIN,
        SUBMITTING_LOGIN,
        WAITING_LOGIN_RESULT,
        NAVIGATING_TO_AD,
        PERFORMING_ACTION,
        DONE,
        ERROR
    }

    private var webView: WebView? = null
    private var state = State.IDLE
    private var currentAction: Action? = null
    private var targetAmount: Int = 200
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var onComplete: ((Boolean, String) -> Unit)? = null

    private val platformName: String
        get() = when (currentAction) {
            Action.BAEMIN_SET_AMOUNT -> "배민"
            Action.COUPANG_AD_ON, Action.COUPANG_AD_OFF -> "쿠팡"
            null -> "?"
        }

    fun isRunning(): Boolean = state != State.IDLE && state != State.DONE && state != State.ERROR

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(0, 0)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    log(Code.INFO_PAGE, "페이지 로드 완료: $url (state=$state)")
                    captureHtmlSnapshot(view, url)
                    handlePageLoaded(url)
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        log(Code.ERR_PAGE_LOAD, "메인프레임 오류: ${error.description} (code=${error.errorCode}, url=${request.url})")
                        finishWithError(Code.ERR_PAGE_LOAD, "페이지 로드 실패: ${error.description}")
                    } else {
                        log(Code.INFO_PAGE, "서브리소스 오류 (무시): ${request.url}")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request.isForMainFrame) {
                        log(Code.ERR_PAGE_LOAD, "HTTP 오류: ${errorResponse.statusCode} ${errorResponse.reasonPhrase} (url=${request.url})")
                        if (errorResponse.statusCode == 403) {
                            finishWithError(Code.ERR_PAGE_LOAD, "접근 차단 (403): ${request.url}")
                        }
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        log(Code.INFO_JS_RESULT, "JS콘솔 [${it.messageLevel()}]: ${it.message()} (line ${it.lineNumber()})")
                    }
                    return true
                }
            }
        }
    }

    /** 현재 페이지 HTML 앞부분 캡처 */
    private fun captureHtmlSnapshot(view: WebView, url: String) {
        view.evaluateJavascript(
            "(function(){ return document.documentElement.outerHTML; })()"
        ) { html ->
            if (html != null && html != "null") {
                val unescaped = html
                    .removeSurrounding("\"")
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                AdActionLog.addHtmlSnapshot(platformName, url, unescaped)
            }
        }
    }

    fun execute(action: Action, amount: Int = 200, callback: (Boolean, String) -> Unit) {
        if (isRunning()) {
            log(Code.ERR_ALREADY_RUNNING, "이미 진행 중: state=$state, action=$currentAction")
            callback(false, "이미 작업 진행 중")
            return
        }

        currentAction = action
        targetAmount = amount
        onComplete = callback

        val actionDesc = when (action) {
            Action.BAEMIN_SET_AMOUNT -> "배민 금액 ${amount}원 변경"
            Action.COUPANG_AD_ON -> "쿠팡 광고 켜기"
            Action.COUPANG_AD_OFF -> "쿠팡 광고 끄기"
        }
        log(Code.INFO_STATE, "작업 시작: $actionDesc")
        changeState(State.LOADING_LOGIN)

        handler.post {
            try {
                webView = createWebView()
                (activity.window.decorView as ViewGroup).addView(webView)
                startTimeout()

                when (action) {
                    Action.BAEMIN_SET_AMOUNT -> {
                        if (!AdManager.hasBaeminCredentials()) {
                            finishWithError(Code.ERR_NO_CREDENTIALS, "배민 로그인 정보가 설정되지 않았습니다")
                            return@post
                        }
                        log(Code.INFO_STATE, "배민 진입: $BAEMIN_ENTRY_URL (→ biz-member 로그인 리다이렉트)")
                        webView?.loadUrl(BAEMIN_ENTRY_URL)
                    }
                    Action.COUPANG_AD_ON, Action.COUPANG_AD_OFF -> {
                        if (!AdManager.hasCoupangCredentials()) {
                            finishWithError(Code.ERR_NO_CREDENTIALS, "쿠팡 로그인 정보가 설정되지 않았습니다")
                            return@post
                        }
                        log(Code.INFO_STATE, "쿠팡 로그인 페이지 로드: $COUPANG_LOGIN_URL")
                        webView?.loadUrl(COUPANG_LOGIN_URL)
                    }
                }
            } catch (e: Exception) {
                log(Code.ERR_WEBVIEW, "WebView 생성 실패: ${e.message}")
                finishWithError(Code.ERR_WEBVIEW, "WebView 오류: ${e.message}")
            }
        }
    }

    private fun handlePageLoaded(url: String) {
        when (currentAction) {
            Action.BAEMIN_SET_AMOUNT -> handleBaeminPage(url)
            Action.COUPANG_AD_ON, Action.COUPANG_AD_OFF -> handleCoupangPage(url)
            null -> {}
        }
    }

    // ───────── 배민 사장님 사이트 자동화 ─────────

    private fun handleBaeminPage(url: String) {
        when {
            // 로그인 페이지 감지 (biz-member.baemin.com 또는 /login 경로)
            url.contains("biz-member") || url.contains("/login") -> {
                if (state == State.WAITING_LOGIN_RESULT) {
                    log(Code.INFO_STATE, "로그인 결과 대기 중, 아직 로그인 페이지: $url")
                    return
                }
                log(Code.INFO_STATE, "배민 로그인 페이지 감지: $url")
                changeState(State.SUBMITTING_LOGIN)
                // React SPA 렌더링 대기 후 폼 입력
                handler.postDelayed({ submitBaeminLogin() }, 2000)
            }

            // 로그인 성공 (WAITING/SUBMITTING → 비로그인 페이지 도달)
            state == State.WAITING_LOGIN_RESULT || state == State.SUBMITTING_LOGIN -> {
                log(Code.INFO_STATE, "배민 로그인 성공! URL: $url")
                changeState(State.NAVIGATING_TO_AD)
                // SPA 렌더링 대기 후 광고 메뉴 탐색
                handler.postDelayed({ navigateBaeminAdMenu() }, 3000)
            }

            // 초기 로드 (self.baemin.com - 리다이렉트 대기)
            state == State.LOADING_LOGIN -> {
                log(Code.INFO_STATE, "초기 페이지 로드, 리다이렉트 대기 (4초): $url")
                // 4초 대기: JS 리다이렉트가 발생하면 onPageFinished 재호출
                // 발생하지 않으면 이미 로그인된 것으로 판단
                handler.postDelayed({
                    if (state == State.LOADING_LOGIN) {
                        val currentUrl = webView?.url ?: ""
                        if (!currentUrl.contains("/login") && !currentUrl.contains("biz-member")) {
                            log(Code.INFO_STATE, "리다이렉트 없음 — 이미 로그인됨, 광고 메뉴 탐색 시작")
                            changeState(State.NAVIGATING_TO_AD)
                            navigateBaeminAdMenu()
                        }
                    }
                }, 4000)
            }

            // SPA 내부 네비게이션 중 (무시)
            else -> {
                log(Code.INFO_STATE, "handleBaeminPage: state=$state, url=$url (무시)")
            }
        }
    }

    /** 배민 로그인 폼 찾기 + 입력 (재시도 포함) */
    private fun submitBaeminLogin(retries: Int = MAX_RETRIES) {
        if (state != State.SUBMITTING_LOGIN) {
            log(Code.INFO_STATE, "배민 로그인 중단 (state=$state)")
            return
        }

        val id = escapeForJs(AdManager.getBaeminId())
        val pw = escapeForJs(AdManager.getBaeminPw())
        log(Code.INFO_STATE, "배민 로그인 폼 찾기 (시도 ${MAX_RETRIES - retries + 1}/$MAX_RETRIES, id=${AdManager.getBaeminId().take(3)}***)")

        val js = """
            (function() {
                var root = document.getElementById('root');
                var rootChildren = root ? root.children.length : -1;

                var idInput = document.querySelector(
                    'input[type="text"], input[name="userId"], input[id*="id"], ' +
                    'input[placeholder*="아이디"], input[autocomplete="username"], input[name="email"]'
                );
                var pwInput = document.querySelector('input[type="password"]');

                if (!idInput || !pwInput) {
                    var allInputs = document.querySelectorAll('input');
                    var inputInfo = '';
                    for (var i = 0; i < Math.min(allInputs.length, 5); i++) {
                        inputInfo += allInputs[i].type + '(' + (allInputs[i].name || allInputs[i].placeholder || '?') + ') ';
                    }
                    return 'NO_FORM|rootChildren=' + rootChildren + ',inputs=' + allInputs.length + ',detail=' + inputInfo;
                }

                var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                nativeSetter.call(idInput, '$id');
                idInput.dispatchEvent(new Event('input', {bubbles: true}));
                idInput.dispatchEvent(new Event('change', {bubbles: true}));

                nativeSetter.call(pwInput, '$pw');
                pwInput.dispatchEvent(new Event('input', {bubbles: true}));
                pwInput.dispatchEvent(new Event('change', {bubbles: true}));

                setTimeout(function() {
                    var btn = document.querySelector(
                        'button[type="submit"], button[class*="login"], button[class*="Login"]'
                    );
                    if (!btn) {
                        var buttons = document.querySelectorAll('button');
                        for (var i = 0; i < buttons.length; i++) {
                            var text = buttons[i].textContent.trim();
                            if (text.indexOf('로그인') >= 0 || text.indexOf('Login') >= 0) {
                                btn = buttons[i];
                                break;
                            }
                        }
                    }
                    if (btn) btn.click();
                    else if (idInput.form) idInput.form.submit();
                }, 500);
                return 'OK|idTag=' + idInput.tagName + '.' + (idInput.name || idInput.type) + ',pwTag=' + pwInput.tagName;
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js) { result ->
            log(Code.INFO_JS_RESULT, "배민 로그인 JS: $result")
            if (result?.contains("NO_FORM") == true) {
                if (retries > 0) {
                    handler.postDelayed({ submitBaeminLogin(retries - 1) }, RETRY_DELAY)
                } else {
                    finishWithError(Code.ERR_NO_LOGIN_FORM, "배민 로그인 폼을 찾을 수 없습니다: $result")
                }
            } else {
                changeState(State.WAITING_LOGIN_RESULT)
            }
        }
    }

    /** 배민 SPA에서 광고 서비스 메뉴 찾기 + 클릭 */
    private fun navigateBaeminAdMenu(retries: Int = MAX_RETRIES) {
        if (state != State.NAVIGATING_TO_AD) {
            log(Code.INFO_STATE, "광고 메뉴 탐색 중단 (state=$state)")
            return
        }
        log(Code.INFO_STATE, "광고 메뉴 탐색 (시도 ${MAX_RETRIES - retries + 1}/$MAX_RETRIES)")

        val js = """
            (function() {
                var items = document.querySelectorAll(
                    'a, button, [role="menuitem"], [role="tab"], nav *, ' +
                    '[class*="menu"] *, [class*="nav"] *, [class*="sidebar"] *, [class*="Menu"] *'
                );
                var debug = 'total=' + items.length;
                for (var i = 0; i < items.length; i++) {
                    var el = items[i];
                    var text = el.textContent.trim();
                    if (text.length > 0 && text.length < 15 &&
                        text.indexOf('광고') >= 0 &&
                        text.indexOf('이용약관') < 0 && text.indexOf('정책') < 0) {
                        el.click();
                        return 'CLICKED|' + text + '|tag=' + el.tagName + ',class=' + (el.className || '').substring(0, 50);
                    }
                }
                var menuItems = [];
                var navEls = document.querySelectorAll('nav a, [class*="menu"] a, [class*="sidebar"] a, [class*="Menu"] a');
                for (var j = 0; j < Math.min(navEls.length, 15); j++) {
                    menuItems.push(navEls[j].textContent.trim().substring(0, 25));
                }
                return 'NOT_FOUND|' + debug + '|menus=[' + menuItems.join(',') + ']';
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js) { result ->
            log(Code.INFO_JS_RESULT, "광고 메뉴 탐색 결과: $result")
            if (result?.contains("CLICKED") == true) {
                // 광고·서비스관리 클릭 후 SPA 페이지 로딩 대기 (5초)
                log(Code.INFO_STATE, "광고 메뉴 클릭 성공, 페이지 로딩 대기 5초...")
                handler.postDelayed({
                    // 로딩 후 HTML 스냅샷 캡처
                    webView?.let { captureHtmlSnapshot(it, "after-ad-menu-click") }
                    navigateBaeminClickAd()
                }, 5000)
            } else if (retries > 0) {
                handler.postDelayed({ navigateBaeminAdMenu(retries - 1) }, RETRY_DELAY)
            } else {
                webView?.let { captureHtmlSnapshot(it, "ad-menu-not-found") }
                finishWithError(Code.ERR_NO_AD_FIELD, "광고 메뉴를 찾을 수 없습니다: $result")
            }
        }
    }

    /** 배민 광고 페이지에서 "우리가게클릭" 찾기 (전체 페이지 + iframe 검색) */
    private fun navigateBaeminClickAd(retries: Int = MAX_RETRIES) {
        if (state != State.NAVIGATING_TO_AD) {
            log(Code.INFO_STATE, "우리가게클릭 탐색 중단 (state=$state)")
            return
        }
        log(Code.INFO_STATE, "우리가게클릭 탐색 (시도 ${MAX_RETRIES - retries + 1}/$MAX_RETRIES)")

        // 페이지 전체(메뉴+본문+탭+카드)에서 검색, iframe 내부도 시도
        val js = """
            (function() {
                function searchInDoc(doc, label) {
                    // 모든 클릭 가능 요소 + 본문 요소 검색
                    var items = doc.querySelectorAll(
                        'a, button, li, span, div, td, h3, h4, p, label, ' +
                        '[role="menuitem"], [role="tab"], [role="button"], ' +
                        '[class*="card"], [class*="Card"], [class*="tab"], [class*="Tab"], ' +
                        '[class*="item"], [class*="Item"], [class*="service"], [class*="Service"]'
                    );
                    for (var i = 0; i < items.length; i++) {
                        var el = items[i];
                        var text = (el.innerText || el.textContent || '').trim();
                        if (text.length > 0 && text.length < 30 &&
                            (text.indexOf('우리가게') >= 0 || text === '클릭광고' ||
                             text.indexOf('우리가게클릭') >= 0 || text.indexOf('CPC') >= 0 ||
                             (text.indexOf('클릭') >= 0 && text.indexOf('광고') >= 0))) {
                            el.click();
                            return 'CLICKED|' + label + '|' + text + '|tag=' + el.tagName;
                        }
                    }
                    // 디버그: 페이지 내 모든 텍스트 요소 수집
                    var allTexts = [];
                    var allEls = doc.querySelectorAll('a, button, span, div, h3, h4, li, [role="tab"], [role="button"]');
                    for (var j = 0; j < allEls.length; j++) {
                        var t = (allEls[j].innerText || allEls[j].textContent || '').trim();
                        if (t.length > 1 && t.length < 30) {
                            var isDup = false;
                            for (var k = 0; k < allTexts.length; k++) {
                                if (allTexts[k] === t) { isDup = true; break; }
                            }
                            if (!isDup) allTexts.push(t);
                        }
                        if (allTexts.length >= 30) break;
                    }
                    return 'NOT_FOUND|' + label + '|texts=[' + allTexts.join(',') + ']';
                }

                // 1. 메인 document 검색
                var result = searchInDoc(document, 'main');
                if (result.indexOf('CLICKED') >= 0) return result;

                // 2. iframe 내부 검색
                var iframes = document.querySelectorAll('iframe');
                for (var f = 0; f < iframes.length; f++) {
                    try {
                        var iDoc = iframes[f].contentDocument || iframes[f].contentWindow.document;
                        if (iDoc) {
                            var iResult = searchInDoc(iDoc, 'iframe' + f);
                            if (iResult.indexOf('CLICKED') >= 0) return iResult;
                        }
                    } catch(e) {}
                }

                return result;
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js) { result ->
            log(Code.INFO_JS_RESULT, "우리가게클릭 탐색 결과: $result")
            if (result?.contains("CLICKED") == true) {
                changeState(State.PERFORMING_ACTION)
                // 우리가게클릭 페이지 로딩 대기 6초
                log(Code.INFO_STATE, "우리가게클릭 클릭 성공, 페이지 로딩 대기 6초...")
                handler.postDelayed({
                    webView?.let { captureHtmlSnapshot(it, "after-click-ad") }
                    performBaeminAmountChange()
                }, 6000)
            } else if (retries > 0) {
                handler.postDelayed({ navigateBaeminClickAd(retries - 1) }, RETRY_DELAY)
            } else {
                webView?.let { captureHtmlSnapshot(it, "click-ad-not-found") }
                finishWithError(Code.ERR_NO_AD_FIELD, "우리가게클릭을 찾을 수 없습니다: $result")
            }
        }
    }

    /** 배민 CPC 광고 금액 변경 */
    private fun performBaeminAmountChange(retries: Int = MAX_RETRIES) {
        if (state != State.PERFORMING_ACTION) return
        log(Code.INFO_STATE, "배민 금액 변경 시도: ${targetAmount}원 (${MAX_RETRIES - retries + 1}/$MAX_RETRIES)")

        val js = """
            (function() {
                // 모든 input 검색 (type 제한 없이)
                var inputs = document.querySelectorAll('input');
                var debugInfo = 'totalInputs=' + inputs.length;
                var found = false;
                var foundEl = null;

                for (var i = 0; i < inputs.length; i++) {
                    var el = inputs[i];
                    if (el.type === 'hidden' || el.type === 'checkbox' || el.type === 'radio') continue;

                    // 부모를 여러 레벨로 올라가며 컨텍스트 텍스트 수집
                    var ctx = '';
                    var p = el.parentElement;
                    for (var lv = 0; lv < 5 && p; lv++) {
                        ctx = (p.innerText || p.textContent || '').replace(/\s+/g, ' ').trim();
                        if (ctx.length > 5) break;
                        p = p.parentElement;
                    }
                    ctx = ctx.substring(0, 100);

                    debugInfo += '|in[' + i + ']=' + el.type + ',val=' + (el.value || '').substring(0, 20) +
                                 ',ph=' + (el.placeholder || '') + ',ctx=' + ctx.substring(0, 60);

                    if (ctx.indexOf('클릭 당') >= 0 || ctx.indexOf('클릭당') >= 0 ||
                        ctx.indexOf('희망') >= 0 || ctx.indexOf('광고금액') >= 0 ||
                        ctx.indexOf('입찰') >= 0 || ctx.indexOf('단가') >= 0 ||
                        ctx.indexOf('CPC') >= 0 || ctx.indexOf('cpc') >= 0 ||
                        (ctx.indexOf('원') >= 0 && ctx.indexOf('금액') >= 0) ||
                        el.placeholder.indexOf('원') >= 0 || el.placeholder.indexOf('금액') >= 0) {
                        foundEl = el;
                        debugInfo += '|MATCHED[' + i + ']';
                        break;
                    }
                }

                // 페이지 내 텍스트 디버그 (못찾았을 때)
                if (!foundEl) {
                    var allTexts = [];
                    var allEls = document.querySelectorAll('span, div, label, h3, h4, p, td, th, button');
                    for (var k = 0; k < allEls.length; k++) {
                        var ch = allEls[k].childNodes;
                        var directText = '';
                        for (var c = 0; c < ch.length; c++) {
                            if (ch[c].nodeType === 3) directText += ch[c].textContent.trim();
                        }
                        if (directText.length > 1 && directText.length < 40) {
                            var isDup = false;
                            for (var d = 0; d < allTexts.length; d++) {
                                if (allTexts[d] === directText) { isDup = true; break; }
                            }
                            if (!isDup) allTexts.push(directText);
                        }
                        if (allTexts.length >= 40) break;
                    }
                    debugInfo += '|pageTexts=[' + allTexts.join('|') + ']';
                }

                if (foundEl) {
                    var nativeSetter = Object.getOwnPropertyDescriptor(
                        window.HTMLInputElement.prototype, 'value').set;
                    nativeSetter.call(foundEl, '$targetAmount');
                    foundEl.dispatchEvent(new Event('input', {bubbles: true}));
                    foundEl.dispatchEvent(new Event('change', {bubbles: true}));
                    foundEl.dispatchEvent(new Event('blur', {bubbles: true}));

                    setTimeout(function() {
                        var btns = document.querySelectorAll('button');
                        for (var j = 0; j < btns.length; j++) {
                            var t = btns[j].textContent.trim();
                            if (t.indexOf('저장') >= 0 || t.indexOf('적용') >= 0 ||
                                t.indexOf('변경') >= 0 || t.indexOf('수정') >= 0 || t.indexOf('확인') >= 0) {
                                btns[j].click();
                                break;
                            }
                        }
                    }, 500);
                    return 'FOUND|' + debugInfo;
                }
                return 'NOT_FOUND|' + debugInfo;
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js) { result ->
            log(Code.INFO_JS_RESULT, "금액 변경 JS: $result")
            if (result?.contains("NOT_FOUND") == true) {
                if (retries > 0) {
                    handler.postDelayed({ performBaeminAmountChange(retries - 1) }, RETRY_DELAY)
                } else {
                    webView?.let { captureHtmlSnapshot(it, "amount-field-not-found") }
                    finishWithError(Code.ERR_NO_AD_FIELD, "광고 금액 입력 필드를 찾을 수 없습니다")
                }
            } else {
                handler.postDelayed({
                    finishWithSuccess("배민 광고 금액 ${targetAmount}원으로 변경")
                }, 2000)
            }
        }
    }

    // ───────── 쿠팡이츠 스토어 자동화 ─────────

    private fun handleCoupangPage(url: String) {
        when {
            // 로그인 페이지 감지
            url.contains("/login") || url.contains("/merchant/login") -> {
                if (state == State.WAITING_LOGIN_RESULT) {
                    log(Code.INFO_STATE, "쿠팡 아직 로그인 페이지: $url")
                    // 2FA(SMS 인증) 감지 시도
                    handler.postDelayed({ detectCoupang2FA() }, 2000)
                    return
                }
                log(Code.INFO_STATE, "쿠팡 로그인 페이지 감지: $url")
                changeState(State.SUBMITTING_LOGIN)
                // 페이지 렌더링 대기 후 폼 입력
                handler.postDelayed({ submitCoupangLogin() }, 2000)
            }

            // 2FA / 인증 페이지 감지
            url.contains("/verify") || url.contains("/otp") || url.contains("/2fa") || url.contains("/auth") -> {
                log(Code.ERR_LOGIN_FAILED, "쿠팡 2단계 인증(2FA) 페이지 감지: $url — 자동화 불가")
                finishWithError(Code.ERR_LOGIN_FAILED, "쿠팡이츠는 SMS 2단계 인증이 필요하여 자동 로그인이 불가합니다")
            }

            // 로그인 성공 (비로그인 페이지 도달)
            state == State.WAITING_LOGIN_RESULT || state == State.SUBMITTING_LOGIN -> {
                log(Code.INFO_STATE, "쿠팡 로그인 성공! URL: $url")
                changeState(State.NAVIGATING_TO_AD)
                handler.postDelayed({ navigateCoupangAdPage() }, 2000)
            }

            else -> {
                log(Code.INFO_STATE, "handleCoupangPage: state=$state, url=$url")
            }
        }
    }

    /** 쿠팡 2FA(SMS 인증) 감지 */
    private fun detectCoupang2FA() {
        val js = """
            (function() {
                var text = document.body ? document.body.innerText : '';
                if (text.indexOf('인증') >= 0 || text.indexOf('SMS') >= 0 || text.indexOf('문자') >= 0 ||
                    text.indexOf('verification') >= 0 || text.indexOf('코드') >= 0 || text.indexOf('code') >= 0) {
                    return '2FA_DETECTED|' + text.substring(0, 200);
                }
                return 'NO_2FA';
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js) { result ->
            if (result?.contains("2FA_DETECTED") == true) {
                log(Code.ERR_LOGIN_FAILED, "쿠팡 2FA 감지: $result")
                finishWithError(Code.ERR_LOGIN_FAILED, "쿠팡이츠 SMS 인증이 필요합니다. 자동 로그인 불가.")
            }
        }
    }

    /** 쿠팡 로그인 폼 찾기 + 입력 (재시도 포함) */
    private fun submitCoupangLogin(retries: Int = MAX_RETRIES) {
        if (state != State.SUBMITTING_LOGIN) {
            log(Code.INFO_STATE, "쿠팡 로그인 중단 (state=$state)")
            return
        }

        val id = escapeForJs(AdManager.getCoupangId())
        val pw = escapeForJs(AdManager.getCoupangPw())
        log(Code.INFO_STATE, "쿠팡 로그인 폼 찾기 (시도 ${MAX_RETRIES - retries + 1}/$MAX_RETRIES, id=${AdManager.getCoupangId().take(3)}***)")

        val js = """
            (function() {
                var idInput = document.querySelector(
                    'input[type="text"], input[type="email"], input[name="username"], ' +
                    'input[name="email"], input[placeholder*="아이디"], input[placeholder*="이메일"], ' +
                    'input[autocomplete="username"], input[autocomplete="email"]'
                );
                var pwInput = document.querySelector('input[type="password"]');

                if (!idInput || !pwInput) {
                    var allInputs = document.querySelectorAll('input');
                    var info = '';
                    for (var i = 0; i < Math.min(allInputs.length, 5); i++) {
                        info += allInputs[i].type + '(' + (allInputs[i].name || allInputs[i].placeholder || '?') + ') ';
                    }
                    return 'NO_FORM|inputs=' + allInputs.length + ',detail=' + info;
                }

                var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                nativeSetter.call(idInput, '$id');
                idInput.dispatchEvent(new Event('input', {bubbles: true}));
                idInput.dispatchEvent(new Event('change', {bubbles: true}));

                nativeSetter.call(pwInput, '$pw');
                pwInput.dispatchEvent(new Event('input', {bubbles: true}));
                pwInput.dispatchEvent(new Event('change', {bubbles: true}));

                setTimeout(function() {
                    var btn = document.querySelector('button[type="submit"], input[type="submit"]');
                    if (!btn) {
                        var buttons = document.querySelectorAll('button');
                        for (var i = 0; i < buttons.length; i++) {
                            var text = buttons[i].textContent.trim();
                            if (text.indexOf('로그인') >= 0 || text.indexOf('Login') >= 0 || text.indexOf('Sign') >= 0) {
                                btn = buttons[i]; break;
                            }
                        }
                    }
                    if (btn) btn.click();
                    else if (idInput.form) idInput.form.submit();
                }, 500);
                return 'OK|idTag=' + idInput.tagName + '.' + (idInput.name || idInput.type);
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js) { result ->
            log(Code.INFO_JS_RESULT, "쿠팡 로그인 JS: $result")
            if (result?.contains("NO_FORM") == true) {
                if (retries > 0) {
                    handler.postDelayed({ submitCoupangLogin(retries - 1) }, RETRY_DELAY)
                } else {
                    finishWithError(Code.ERR_NO_LOGIN_FORM, "쿠팡 로그인 폼을 찾을 수 없습니다: $result")
                }
            } else {
                changeState(State.WAITING_LOGIN_RESULT)
            }
        }
    }

    /** 쿠팡 광고 관리 페이지 탐색 - 메뉴 클릭 + href 추출 + URL 직접 이동 */
    private fun navigateCoupangAdPage(retries: Int = MAX_RETRIES) {
        if (state != State.NAVIGATING_TO_AD) return
        log(Code.INFO_STATE, "쿠팡 광고관리 페이지 탐색 (시도 ${MAX_RETRIES - retries + 1}/$MAX_RETRIES)")

        // 메뉴에서 "광고 관리" 링크의 href를 찾아서 직접 이동
        val js = """
            (function() {
                var items = document.querySelectorAll('a, [role="menuitem"]');
                var debug = 'total=' + items.length;
                var adLink = null;
                var adHref = '';

                for (var i = 0; i < items.length; i++) {
                    var el = items[i];
                    var text = (el.innerText || el.textContent || '').trim();
                    if (text.length > 0 && text.length < 20 &&
                        text.indexOf('광고') >= 0 && text.indexOf('약관') < 0) {
                        adLink = el;
                        adHref = el.href || el.getAttribute('href') || '';
                        debug += '|found=' + text + ',href=' + adHref + ',tag=' + el.tagName;
                        break;
                    }
                }

                if (adLink) {
                    // href가 있으면 직접 이동 (SPA 라우터 우회)
                    if (adHref && adHref.indexOf('#') < 0 && adHref.length > 1) {
                        window.location.href = adHref;
                        return 'NAVIGATING|' + debug;
                    }
                    // href 없으면 클릭
                    adLink.click();
                    return 'CLICKED|' + debug;
                }

                // 메뉴 항목 수집 (디버그)
                var menuItems = [];
                var navEls = document.querySelectorAll('a');
                for (var j = 0; j < navEls.length; j++) {
                    var t = (navEls[j].innerText || navEls[j].textContent || '').trim();
                    var h = navEls[j].href || '';
                    if (t.length > 0 && t.length < 25) menuItems.push(t + '→' + h.substring(h.lastIndexOf('/')));
                    if (menuItems.length >= 20) break;
                }
                return 'NOT_FOUND|' + debug + '|links=[' + menuItems.join(',') + ']';
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js) { result ->
            log(Code.INFO_JS_RESULT, "쿠팡 광고관리 탐색: $result")
            if (result?.contains("NAVIGATING") == true || result?.contains("CLICKED") == true) {
                changeState(State.PERFORMING_ACTION)
                // 광고 페이지 로딩 대기 (8초 - URL 이동은 더 오래 걸림)
                log(Code.INFO_STATE, "광고 관리 이동 성공, 페이지 로딩 대기 8초...")
                handler.postDelayed({
                    webView?.let { captureHtmlSnapshot(it, "after-coupang-ad-click") }
                    performCoupangToggle()
                }, 8000)
            } else if (retries > 0) {
                handler.postDelayed({ navigateCoupangAdPage(retries - 1) }, RETRY_DELAY)
            } else {
                webView?.let { captureHtmlSnapshot(it, "coupang-ad-menu-not-found") }
                finishWithError(Code.ERR_NO_AD_FIELD, "쿠팡 광고관리 메뉴를 찾을 수 없습니다")
            }
        }
    }

    /** 쿠팡 광고 토글 (wujie iframe 내부까지 검색) */
    private fun performCoupangToggle(retries: Int = MAX_RETRIES) {
        if (state != State.PERFORMING_ACTION) return
        val turnOn = currentAction == Action.COUPANG_AD_ON
        val onOff = if (turnOn) "켜기" else "끄기"
        log(Code.INFO_STATE, "쿠팡 광고 $onOff 시도 (${MAX_RETRIES - retries + 1}/$MAX_RETRIES)")

        val js = """
            (function() {
                function searchToggleInDoc(doc, label) {
                    var debugInfo = label + ':';

                    // 1. 토글/스위치/체크박스 검색
                    var toggles = doc.querySelectorAll(
                        'input[type="checkbox"], button[role="switch"], ' +
                        '[class*="toggle"], [class*="switch"], [class*="Toggle"], [class*="Switch"], ' +
                        '[class*="ant-switch"], [class*="el-switch"], [class*="arco-switch"]');
                    debugInfo += 'toggles=' + toggles.length;

                    for (var i = 0; i < toggles.length; i++) {
                        var parent = toggles[i].closest('div, label, section, tr, [class*="card"], [class*="Card"]');
                        var text = parent ? parent.textContent.substring(0, 150) : '';
                        debugInfo += '|t[' + i + ']=' + toggles[i].tagName + '.' + (toggles[i].className || '').substring(0, 30) + ',ctx=' + text.replace(/\s+/g,' ').trim().substring(0, 50);
                        if (text.indexOf('광고') >= 0 || text.indexOf('켜짐') >= 0 ||
                            text.indexOf('꺼짐') >= 0 || text.indexOf('노출') >= 0 ||
                            text.indexOf('캠페인') >= 0 || text.indexOf('상태') >= 0 ||
                            text.indexOf('활성') >= 0 || text.indexOf('진행') >= 0) {
                            return {found: true, el: toggles[i], debug: debugInfo + '|MATCHED[' + i + ']'};
                        }
                    }

                    // 2. 버튼 텍스트 검색
                    var buttons = doc.querySelectorAll('button, a, span[role="button"], div[role="button"]');
                    debugInfo += '|buttons=' + buttons.length;
                    for (var j = 0; j < buttons.length; j++) {
                        var t = (buttons[j].innerText || buttons[j].textContent || '').trim().substring(0, 30);
                        if (${if (turnOn)
                            "t.indexOf('켜기') >= 0 || t.indexOf('켜짐') >= 0 || t.indexOf('시작') >= 0 || t.indexOf('활성') >= 0 || t === 'ON' || t.indexOf('광고 시작') >= 0 || t.indexOf('재개') >= 0"
                        else
                            "t.indexOf('끄기') >= 0 || t.indexOf('꺼짐') >= 0 || t.indexOf('중지') >= 0 || t.indexOf('비활성') >= 0 || t.indexOf('일시정지') >= 0 || t === 'OFF' || t.indexOf('광고 중지') >= 0 || t.indexOf('정지') >= 0"
                        }) {
                            return {found: true, el: buttons[j], debug: debugInfo + '|btnMATCH[' + j + ']=' + t};
                        }
                    }

                    // 3. 페이지 내 텍스트 수집 (디버그)
                    var allTexts = [];
                    var allEls = doc.querySelectorAll('button, a, span, div, label, h3, h4, td');
                    for (var k = 0; k < allEls.length; k++) {
                        var txt = (allEls[k].innerText || allEls[k].textContent || '').trim();
                        if (txt.length > 1 && txt.length < 25) {
                            var isDup = false;
                            for (var d = 0; d < allTexts.length; d++) {
                                if (allTexts[d] === txt) { isDup = true; break; }
                            }
                            if (!isDup) allTexts.push(txt);
                        }
                        if (allTexts.length >= 25) break;
                    }
                    debugInfo += '|texts=[' + allTexts.join(',') + ']';

                    return {found: false, el: null, debug: debugInfo};
                }

                // 1. 메인 document 검색
                var result = searchToggleInDoc(document, 'main');
                if (result.found) {
                    var el = result.el;
                    if (el.type === 'checkbox') {
                        var isOn = el.checked;
                        if ((${turnOn} && !isOn) || (!${turnOn} && isOn)) el.click();
                    } else {
                        el.click();
                    }
                    return 'SUCCESS|' + result.debug;
                }

                // 2. iframe 내부 검색 (wujie 마이크로프론트엔드)
                var iframes = document.querySelectorAll('iframe');
                var iframeDebug = '|iframes=' + iframes.length;
                for (var f = 0; f < iframes.length; f++) {
                    try {
                        var iDoc = iframes[f].contentDocument || iframes[f].contentWindow.document;
                        if (iDoc) {
                            iframeDebug += '|iframe' + f + '=' + iframes[f].src.substring(0, 50);
                            var iResult = searchToggleInDoc(iDoc, 'iframe' + f);
                            if (iResult.found) {
                                var iel = iResult.el;
                                if (iel.type === 'checkbox') {
                                    var iOn = iel.checked;
                                    if ((${turnOn} && !iOn) || (!${turnOn} && iOn)) iel.click();
                                } else {
                                    iel.click();
                                }
                                return 'SUCCESS|' + iResult.debug;
                            }
                            iframeDebug += '|' + iResult.debug;
                        }
                    } catch(e) {
                        iframeDebug += '|iframe' + f + '_err=' + e.message;
                    }
                }

                // 3. shadow DOM 검색
                var shadows = document.querySelectorAll('*');
                var shadowCount = 0;
                for (var s = 0; s < shadows.length; s++) {
                    if (shadows[s].shadowRoot) {
                        shadowCount++;
                        try {
                            var sResult = searchToggleInDoc(shadows[s].shadowRoot, 'shadow' + shadowCount);
                            if (sResult.found) {
                                var sel = sResult.el;
                                if (sel.type === 'checkbox') {
                                    var sOn = sel.checked;
                                    if ((${turnOn} && !sOn) || (!${turnOn} && sOn)) sel.click();
                                } else {
                                    sel.click();
                                }
                                return 'SUCCESS|' + sResult.debug;
                            }
                        } catch(e) {}
                    }
                }

                return 'NOT_FOUND|' + result.debug + iframeDebug + '|shadows=' + shadowCount;
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js) { result ->
            log(Code.INFO_JS_RESULT, "쿠팡 토글 JS: $result")
            if (result?.contains("NOT_FOUND") == true) {
                if (retries > 0) {
                    handler.postDelayed({ performCoupangToggle(retries - 1) }, RETRY_DELAY)
                } else {
                    webView?.let { captureHtmlSnapshot(it, "coupang-toggle-not-found") }
                    finishWithError(Code.ERR_NO_TOGGLE, "쿠팡 광고 토글을 찾을 수 없습니다")
                }
            } else {
                handler.postDelayed({
                    AdManager.setCoupangAdOn(turnOn)
                    finishWithSuccess("쿠팡 광고 $onOff 완료")
                }, 2000)
            }
        }
    }

    // ───────── 유틸리티 ─────────

    private fun log(code: String, message: String) {
        Log.d(TAG, "[$code] $message")
        AdActionLog.add(code, platformName, message)
    }

    private fun changeState(newState: State) {
        val old = state
        state = newState
        log(Code.INFO_STATE, "상태: $old → $newState")
    }

    private fun escapeForJs(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun startTimeout() {
        timeoutRunnable = Runnable {
            finishWithError(Code.ERR_TIMEOUT, "시간 초과 (${TIMEOUT_MS / 1000}초, state=$state)")
        }
        handler.postDelayed(timeoutRunnable!!, TIMEOUT_MS)
    }

    private fun finishWithSuccess(message: String) {
        cancelTimeout()
        changeState(State.DONE)
        log(Code.OK, message)
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        AdManager.setLastAdAction("${sdf.format(Date())} $message")
        cleanup()
        onComplete?.invoke(true, message)
        onComplete = null
    }

    private fun finishWithError(code: String, message: String) {
        cancelTimeout()
        changeState(State.ERROR)
        log(code, message)
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        AdManager.setLastAdAction("${sdf.format(Date())} 실패[$code]: $message")
        cleanup()
        onComplete?.invoke(false, "[$code] $message")
        onComplete = null
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun cleanup() {
        handler.post {
            webView?.let {
                it.stopLoading()
                it.loadUrl("about:blank")
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
            webView = null
            state = State.IDLE
            currentAction = null
        }
    }
}
