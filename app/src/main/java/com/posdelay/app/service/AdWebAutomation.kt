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
                handler.postDelayed({ navigateBaeminClickAd() }, 2500)
            } else if (retries > 0) {
                handler.postDelayed({ navigateBaeminAdMenu(retries - 1) }, RETRY_DELAY)
            } else {
                webView?.let { captureHtmlSnapshot(it, "ad-menu-not-found") }
                finishWithError(Code.ERR_NO_AD_FIELD, "광고 메뉴를 찾을 수 없습니다: $result")
            }
        }
    }

    /** 배민 우리가게클릭 서브메뉴 찾기 + 클릭 */
    private fun navigateBaeminClickAd(retries: Int = MAX_RETRIES) {
        if (state != State.NAVIGATING_TO_AD) {
            log(Code.INFO_STATE, "우리가게클릭 탐색 중단 (state=$state)")
            return
        }
        log(Code.INFO_STATE, "우리가게클릭 메뉴 탐색 (시도 ${MAX_RETRIES - retries + 1}/$MAX_RETRIES)")

        val js = """
            (function() {
                var items = document.querySelectorAll(
                    'a, button, li, [role="menuitem"], [class*="sub"] *, [class*="menu"] *, [class*="Menu"] *'
                );
                var debug = 'total=' + items.length;
                for (var i = 0; i < items.length; i++) {
                    var el = items[i];
                    var text = el.textContent.trim();
                    if (text.length > 0 && text.length < 20 &&
                        (text.indexOf('우리가게') >= 0 || (text.indexOf('클릭') >= 0 && text.indexOf('광고') >= 0) ||
                         text.indexOf('CPC') >= 0 || text.indexOf('우리가게클릭') >= 0)) {
                        el.click();
                        return 'CLICKED|' + text + '|tag=' + el.tagName;
                    }
                }
                var subItems = [];
                var subs = document.querySelectorAll('[class*="sub"] a, [class*="menu"] a, [class*="Menu"] a, li a');
                for (var j = 0; j < Math.min(subs.length, 15); j++) {
                    var t = subs[j].textContent.trim();
                    if (t.length > 0 && t.length < 30) subItems.push(t);
                }
                return 'NOT_FOUND|' + debug + '|subs=[' + subItems.join(',') + ']';
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js) { result ->
            log(Code.INFO_JS_RESULT, "우리가게클릭 탐색 결과: $result")
            if (result?.contains("CLICKED") == true) {
                changeState(State.PERFORMING_ACTION)
                handler.postDelayed({ performBaeminAmountChange() }, 2500)
            } else if (retries > 0) {
                handler.postDelayed({ navigateBaeminClickAd(retries - 1) }, RETRY_DELAY)
            } else {
                webView?.let { captureHtmlSnapshot(it, "click-ad-not-found") }
                finishWithError(Code.ERR_NO_AD_FIELD, "우리가게클릭 메뉴를 찾을 수 없습니다: $result")
            }
        }
    }

    /** 배민 CPC 광고 금액 변경 */
    private fun performBaeminAmountChange(retries: Int = MAX_RETRIES) {
        if (state != State.PERFORMING_ACTION) return
        log(Code.INFO_STATE, "배민 금액 변경 시도: ${targetAmount}원 (${MAX_RETRIES - retries + 1}/$MAX_RETRIES)")

        val js = """
            (function() {
                var inputs = document.querySelectorAll('input[type="number"], input[type="text"], input[inputmode="numeric"]');
                var debugInfo = 'totalInputs=' + inputs.length;
                var found = false;
                for (var i = 0; i < inputs.length; i++) {
                    var el = inputs[i];
                    var parent = el.closest('label, .form-group, div, tr, li, section');
                    var text = parent ? parent.textContent.substring(0, 150) : '(no parent)';
                    debugInfo += '|input[' + i + ']=' + el.type + ',val=' + el.value + ',ctx=' + text.replace(/\s+/g,' ').trim().substring(0, 60);
                    if (text.indexOf('클릭 당') >= 0 || text.indexOf('클릭당') >= 0 ||
                        text.indexOf('희망 광고금액') >= 0 || text.indexOf('희망광고금액') >= 0 ||
                        text.indexOf('입찰') >= 0 || text.indexOf('단가') >= 0 ||
                        text.indexOf('CPC') >= 0 || text.indexOf('cpc') >= 0 ||
                        (text.indexOf('원') >= 0 && text.indexOf('금액') >= 0)) {
                        var nativeSetter = Object.getOwnPropertyDescriptor(
                            window.HTMLInputElement.prototype, 'value').set;
                        nativeSetter.call(el, '$targetAmount');
                        el.dispatchEvent(new Event('input', {bubbles: true}));
                        el.dispatchEvent(new Event('change', {bubbles: true}));
                        found = true;
                        debugInfo += '|MATCHED[' + i + ']';
                        break;
                    }
                }
                if (found) {
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

    /** 쿠팡 광고 관리 페이지 탐색 */
    private fun navigateCoupangAdPage(retries: Int = MAX_RETRIES) {
        if (state != State.NAVIGATING_TO_AD) return
        log(Code.INFO_STATE, "쿠팡 광고관리 페이지 탐색 (시도 ${MAX_RETRIES - retries + 1}/$MAX_RETRIES)")

        val js = """
            (function() {
                var items = document.querySelectorAll(
                    'a, button, [role="menuitem"], nav *, [class*="menu"] *, [class*="sidebar"] *, [class*="Menu"] *'
                );
                var debug = 'total=' + items.length;
                for (var i = 0; i < items.length; i++) {
                    var el = items[i];
                    var text = el.textContent.trim();
                    if (text.length > 0 && text.length < 15 &&
                        (text.indexOf('광고') >= 0 || text.indexOf('마케팅') >= 0)) {
                        if (el.href) debug += '|clickLink=' + el.href;
                        el.click();
                        return 'CLICKED|' + text;
                    }
                }
                var menuItems = [];
                var navEls = document.querySelectorAll('nav a, [class*="menu"] a, [class*="sidebar"] a, [class*="Menu"] a');
                for (var j = 0; j < Math.min(navEls.length, 15); j++) {
                    var t = navEls[j].textContent.trim();
                    if (t.length > 0 && t.length < 25) menuItems.push(t);
                }
                return 'NOT_FOUND|' + debug + '|menus=[' + menuItems.join(',') + ']';
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js) { result ->
            log(Code.INFO_JS_RESULT, "쿠팡 광고관리 탐색: $result")
            if (result?.contains("CLICKED") == true) {
                changeState(State.PERFORMING_ACTION)
                handler.postDelayed({ performCoupangToggle() }, 2500)
            } else if (retries > 0) {
                handler.postDelayed({ navigateCoupangAdPage(retries - 1) }, RETRY_DELAY)
            } else {
                webView?.let { captureHtmlSnapshot(it, "coupang-ad-menu-not-found") }
                finishWithError(Code.ERR_NO_AD_FIELD, "쿠팡 광고관리 메뉴를 찾을 수 없습니다")
            }
        }
    }

    /** 쿠팡 광고 토글 */
    private fun performCoupangToggle(retries: Int = MAX_RETRIES) {
        if (state != State.PERFORMING_ACTION) return
        val turnOn = currentAction == Action.COUPANG_AD_ON
        val onOff = if (turnOn) "켜기" else "끄기"
        log(Code.INFO_STATE, "쿠팡 광고 $onOff 시도 (${MAX_RETRIES - retries + 1}/$MAX_RETRIES)")

        val js = """
            (function() {
                var debugInfo = '';
                var toggles = document.querySelectorAll(
                    'input[type="checkbox"], button[role="switch"], [class*="toggle"], [class*="switch"], [class*="Toggle"], [class*="Switch"]');
                debugInfo += 'toggles=' + toggles.length;
                var toggle = null;

                for (var i = 0; i < toggles.length; i++) {
                    var parent = toggles[i].closest('div, label, section, tr, [class*="card"], [class*="Card"]');
                    var text = parent ? parent.textContent.substring(0, 100) : '';
                    debugInfo += '|t[' + i + ']=' + toggles[i].tagName + ',ctx=' + text.replace(/\s+/g,' ').trim().substring(0, 40);
                    if (text.indexOf('광고') >= 0 || text.indexOf('켜짐') >= 0 ||
                        text.indexOf('꺼짐') >= 0 || text.indexOf('노출') >= 0 ||
                        text.indexOf('캠페인') >= 0 || text.indexOf('상태') >= 0) {
                        toggle = toggles[i];
                        debugInfo += '|MATCHED[' + i + ']';
                        break;
                    }
                }

                if (!toggle) {
                    var buttons = document.querySelectorAll('button, a');
                    debugInfo += '|buttons=' + buttons.length;
                    for (var j = 0; j < buttons.length; j++) {
                        var t = buttons[j].textContent.trim().substring(0, 30);
                        if (${if (turnOn)
                            "t.indexOf('켜기') >= 0 || t.indexOf('켜짐') >= 0 || t.indexOf('시작') >= 0 || t.indexOf('활성') >= 0 || t.indexOf('ON') >= 0"
                        else
                            "t.indexOf('끄기') >= 0 || t.indexOf('꺼짐') >= 0 || t.indexOf('중지') >= 0 || t.indexOf('비활성') >= 0 || t.indexOf('일시정지') >= 0 || t.indexOf('OFF') >= 0"
                        }) {
                            toggle = buttons[j];
                            debugInfo += '|btnMATCH[' + j + ']=' + t;
                            break;
                        }
                    }
                }

                if (toggle) {
                    if (toggle.type === 'checkbox') {
                        var isOn = toggle.checked;
                        debugInfo += '|checkbox=' + isOn;
                        if ((${turnOn} && !isOn) || (!${turnOn} && isOn)) {
                            toggle.click();
                        }
                    } else {
                        toggle.click();
                    }
                    return 'SUCCESS|' + debugInfo;
                }
                return 'NOT_FOUND|' + debugInfo;
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
