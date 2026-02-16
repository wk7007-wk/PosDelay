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
        private const val BAEMIN_ENTRY_URL = "https://self.baemin.com"
        private const val COUPANG_LOGIN_URL = "https://store.coupangeats.com/merchant/login"
        private const val TIMEOUT_MS = 45000L
        private const val RETRY_DELAY = 3000L
        private const val MAX_RETRIES = 2
    }

    enum class Action { BAEMIN_SET_AMOUNT, COUPANG_AD_ON, COUPANG_AD_OFF }

    enum class State {
        IDLE, LOADING_LOGIN, SUBMITTING_LOGIN, WAITING_LOGIN_RESULT,
        NAVIGATING_TO_AD, PERFORMING_ACTION, DONE, ERROR
    }

    private var webView: WebView? = null
    private var state = State.IDLE
    private var currentAction: Action? = null
    private var targetAmount: Int = 200
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var onComplete: ((Boolean, String) -> Unit)? = null
    private var testMode = false

    private val platformName: String
        get() = when (currentAction) {
            Action.BAEMIN_SET_AMOUNT -> "배민"
            Action.COUPANG_AD_ON, Action.COUPANG_AD_OFF -> "쿠팡"
            null -> "?"
        }

    fun isRunning(): Boolean = state != State.IDLE && state != State.DONE && state != State.ERROR

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        // 쿠팡 CMG 모듈은 데스크톱 해상도 + UA 필요
        val isCoupang = currentAction == Action.COUPANG_AD_ON || currentAction == Action.COUPANG_AD_OFF
        val vpWidth = if (isCoupang) 1280 else 480
        val vpHeight = if (isCoupang) 900 else 800

        return WebView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(vpWidth, vpHeight)
            translationX = -10000f
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = true
            settings.userAgentString = if (isCoupang)
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            else
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            if (isCoupang) {
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    log(Code.INFO_PAGE, "페이지 로드: $url (state=$state)")
                    handlePageLoaded(url)
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        log(Code.ERR_PAGE_LOAD, "오류: ${error.description} (url=${request.url})")
                        finishWithError(Code.ERR_PAGE_LOAD, "페이지 로드 실패: ${error.description}")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request.isForMainFrame && errorResponse.statusCode == 403) {
                        log(Code.ERR_PAGE_LOAD, "접근 차단 (403): ${request.url}")
                        finishWithError(Code.ERR_PAGE_LOAD, "접근 차단 (403)")
                    }
                }
            }

            // JS 콘솔 로그는 생략 (트래픽 절감)
            webChromeClient = WebChromeClient()
        }
    }

    /** 에러 시에만 HTML 앞부분 캡처 (500자) */
    private fun captureHtmlOnError(label: String) {
        webView?.evaluateJavascript(
            "(function(){ return document.body ? document.body.innerText.substring(0,500) : 'empty'; })()"
        ) { text ->
            if (text != null && text != "null") {
                log(Code.INFO_JS_RESULT, "[$label] 페이지텍스트: ${text.take(300)}")
            }
        }
    }

    fun executeTest(action: Action, amount: Int = 200, callback: (Boolean, String) -> Unit) {
        testMode = true
        if (isRunning()) { callback(false, "이미 작업 진행 중"); return }
        currentAction = action
        targetAmount = amount
        onComplete = callback

        log(Code.INFO_STATE, "[테스트] 시작: ${action.name}, 금액=$amount")
        changeState(State.NAVIGATING_TO_AD)

        handler.post {
            try {
                webView = createWebView()
                (activity.window.decorView as ViewGroup).addView(webView)
                startTimeout()
                val mockUrl = when (action) {
                    Action.BAEMIN_SET_AMOUNT -> "file:///android_asset/mock_baemin.html"
                    Action.COUPANG_AD_ON, Action.COUPANG_AD_OFF -> "file:///android_asset/mock_coupang.html"
                }
                log(Code.INFO_STATE, "[테스트] 로드: $mockUrl")
                webView?.loadUrl(mockUrl)
            } catch (e: Exception) {
                finishWithError(Code.ERR_WEBVIEW, "[테스트] WebView 오류: ${e.message}")
            }
        }
    }

    fun execute(action: Action, amount: Int = 200, callback: (Boolean, String) -> Unit) {
        testMode = false
        if (isRunning()) { callback(false, "이미 작업 진행 중"); return }
        currentAction = action
        targetAmount = amount
        onComplete = callback

        log(Code.INFO_STATE, "작업 시작: ${action.name}, 금액=$amount")
        changeState(State.LOADING_LOGIN)

        handler.post {
            try {
                webView = createWebView()
                (activity.window.decorView as ViewGroup).addView(webView)
                startTimeout()
                when (action) {
                    Action.BAEMIN_SET_AMOUNT -> {
                        if (!AdManager.hasBaeminCredentials()) {
                            finishWithError(Code.ERR_NO_CREDENTIALS, "배민 로그인 정보 없음"); return@post
                        }
                        webView?.loadUrl(BAEMIN_ENTRY_URL)
                    }
                    Action.COUPANG_AD_ON, Action.COUPANG_AD_OFF -> {
                        if (!AdManager.hasCoupangCredentials()) {
                            finishWithError(Code.ERR_NO_CREDENTIALS, "쿠팡 로그인 정보 없음"); return@post
                        }
                        webView?.loadUrl(COUPANG_LOGIN_URL)
                    }
                }
            } catch (e: Exception) {
                finishWithError(Code.ERR_WEBVIEW, "WebView 오류: ${e.message}")
            }
        }
    }

    private fun handlePageLoaded(url: String) {
        if (testMode && url.startsWith("file://")) {
            if (state == State.NAVIGATING_TO_AD) {
                handler.postDelayed({
                    when (currentAction) {
                        Action.BAEMIN_SET_AMOUNT -> {
                            // 배민은 API 직접 호출 방식이므로 테스트에서는 성공 시뮬레이션
                            changeState(State.PERFORMING_ACTION)
                            finishWithSuccess("[테스트] 배민 API 호출 시뮬레이션 성공 (${targetAmount}원)")
                        }
                        Action.COUPANG_AD_ON, Action.COUPANG_AD_OFF -> {
                            changeState(State.PERFORMING_ACTION)
                            finishWithSuccess("[테스트] 쿠팡 API 호출 시뮬레이션 성공")
                        }
                        null -> {}
                    }
                }, 2000)
            }
            return
        }
        when (currentAction) {
            Action.BAEMIN_SET_AMOUNT -> handleBaeminPage(url)
            Action.COUPANG_AD_ON, Action.COUPANG_AD_OFF -> handleCoupangPage(url)
            null -> {}
        }
    }

    // ───────── 배민 ─────────

    private fun handleBaeminPage(url: String) {
        when {
            url.contains("biz-member") || url.contains("/login") -> {
                if (state == State.WAITING_LOGIN_RESULT) return
                changeState(State.SUBMITTING_LOGIN)
                handler.postDelayed({ submitBaeminLogin() }, 2000)
            }
            state == State.WAITING_LOGIN_RESULT || state == State.SUBMITTING_LOGIN -> {
                log(Code.INFO_STATE, "배민 로그인 성공")
                changeState(State.PERFORMING_ACTION)
                handler.postDelayed({ callBaeminBidApi() }, 2000)
            }
            state == State.LOADING_LOGIN -> {
                handler.postDelayed({
                    if (state == State.LOADING_LOGIN) {
                        val cur = webView?.url ?: ""
                        if (!cur.contains("/login") && !cur.contains("biz-member")) {
                            log(Code.INFO_STATE, "이미 로그인됨")
                            changeState(State.PERFORMING_ACTION)
                            callBaeminBidApi()
                        }
                    }
                }, 4000)
            }
            else -> {}
        }
    }

    private fun submitBaeminLogin(retries: Int = MAX_RETRIES) {
        if (state != State.SUBMITTING_LOGIN) return
        val id = escapeForJs(AdManager.getBaeminId())
        val pw = escapeForJs(AdManager.getBaeminPw())

        val js = """
            (function() {
                var idInput = document.querySelector('input[type="text"], input[name="userId"], input[placeholder*="아이디"], input[autocomplete="username"]');
                var pwInput = document.querySelector('input[type="password"]');
                if (!idInput || !pwInput) return 'NO_FORM|inputs=' + document.querySelectorAll('input').length;
                var s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                s.call(idInput, '$id'); idInput.dispatchEvent(new Event('input', {bubbles:true}));
                s.call(pwInput, '$pw'); pwInput.dispatchEvent(new Event('input', {bubbles:true}));
                setTimeout(function() {
                    var btn = document.querySelector('button[type="submit"]');
                    if (!btn) { var bs = document.querySelectorAll('button'); for (var i=0;i<bs.length;i++) { if (bs[i].textContent.indexOf('로그인')>=0) { btn=bs[i]; break; }}}
                    if (btn) btn.click(); else if (idInput.form) idInput.form.submit();
                }, 500);
                return 'OK';
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js) { result ->
            log(Code.INFO_JS_RESULT, "배민 로그인: $result")
            if (result?.contains("NO_FORM") == true) {
                if (retries > 0) handler.postDelayed({ submitBaeminLogin(retries - 1) }, RETRY_DELAY)
                else finishWithError(Code.ERR_NO_LOGIN_FORM, "배민 로그인 폼 없음: $result")
            } else changeState(State.WAITING_LOGIN_RESULT)
        }
    }

    /** 배민: 로그인 후 API 직접 호출로 CPC 입찰가 변경 */
    private fun callBaeminBidApi() {
        if (state != State.PERFORMING_ACTION) return

        // 1단계: URL에서 shopNumber 추출
        // 2단계: 캠페인 목록 API로 CPC 캠페인 ID 조회
        // 3단계: 입찰가 변경 PUT
        val js = """
            (function() {
                window.__baeminApiResult = null;
                var shopNo = null;
                var m = window.location.href.match(/shops\/(\d+)/);
                if (m) shopNo = m[1];
                if (!shopNo) {
                    var links = document.querySelectorAll('a[href*="/shops/"]');
                    for (var i=0;i<links.length;i++) {
                        var mm = links[i].href.match(/shops\/(\d+)/);
                        if (mm) { shopNo = mm[1]; break; }
                    }
                }
                if (!shopNo) { window.__baeminApiResult = 'ERR|shopNumber없음'; return 'NO_SHOP'; }

                // 1단계: 캠페인 목록에서 CPC(우리가게클릭) 캠페인 ID 찾기
                fetch('https://self-api.baemin.com/v2/ad-center/ad-campaigns/operating-ad-campaign/by-shop-number?shopNumber=' + shopNo, {
                    method: 'GET', credentials: 'include',
                    headers: {'Accept':'application/json','service-channel':'SELF_SERVICE_PC'}
                })
                .then(function(r) { return r.json(); })
                .then(function(campaigns) {
                    if (!Array.isArray(campaigns) || campaigns.length === 0) {
                        window.__baeminApiResult = 'ERR|캠페인없음|type=' + typeof campaigns;
                        return;
                    }
                    // CPC 캠페인 찾기: adInventoryKey=CENTRAL_CPC 또는 adKind.adType=CPC
                    var cpcCampaign = null;
                    for (var i=0;i<campaigns.length;i++) {
                        var c = campaigns[i];
                        if (c.adInventoryKey === 'CENTRAL_CPC' ||
                            (c.adKind && c.adKind.adType === 'CPC') ||
                            (c.adKind && c.adKind.adKindKey === 'WOORI_SHOP_CLICK')) {
                            cpcCampaign = c; break;
                        }
                    }
                    if (!cpcCampaign) {
                        var keys = campaigns.map(function(c){return (c.adInventoryKey||'?')+'(id='+c.id+')'});
                        window.__baeminApiResult = 'ERR|CPC캠페인없음|목록=' + keys.join(',');
                        return;
                    }
                    var cid = cpcCampaign.id;

                    // 2단계: 현재 입찰가 조회
                    return fetch('https://self-api.baemin.com/v4/cpc/bookings/by-shop-number?shopNumber=' + shopNo + '&adCampaignId=' + cid, {
                        method: 'GET', credentials: 'include',
                        headers: {'Accept':'application/json','service-channel':'SELF_SERVICE_PC'}
                    })
                    .then(function(r2) { return r2.json(); })
                    .then(function(booking) {
                        var curBid = (booking && booking.bid) || 0;
                        // 3단계: 입찰가 변경
                        return fetch('https://self-api.baemin.com/v4/cpc/bookings/' + cid + '/bid-budget', {
                            method: 'PUT', credentials: 'include',
                            headers: {'Accept':'application/json','Content-Type':'application/json;charset=UTF-8','service-channel':'SELF_SERVICE_PC'},
                            body: JSON.stringify({adCampaignId:cid, newBid:$targetAmount, newBudget:null, isAutoBidding:false})
                        })
                        .then(function(r3) { return r3.json(); })
                        .then(function(res) {
                            if (res && res.isSuccess) window.__baeminApiResult = 'OK|cid=' + cid + '|' + curBid + '→$targetAmount';
                            else window.__baeminApiResult = 'FAIL|' + JSON.stringify(res).substring(0,100);
                        });
                    });
                })
                .catch(function(e) { window.__baeminApiResult = 'ERR|' + e.message; });
                return 'STARTED|shop=' + shopNo;
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js) { result ->
            log(Code.INFO_JS_RESULT, "배민API시작: $result")
            // Promise 결과를 폴링으로 확인 (최대 15초)
            handler.postDelayed({ pollBaeminApiResult(5) }, 3000)
        }
    }

    /** 배민: API 비동기 결과 폴링 */
    private fun pollBaeminApiResult(retries: Int) {
        if (state != State.PERFORMING_ACTION) return

        webView?.evaluateJavascript(
            "(function(){ return window.__baeminApiResult || 'PENDING'; })()"
        ) { result ->
            val r = result?.removeSurrounding("\"") ?: "PENDING"
            log(Code.INFO_JS_RESULT, "배민API폴링($retries): $r")
            when {
                r == "PENDING" -> {
                    if (retries > 0) handler.postDelayed({ pollBaeminApiResult(retries - 1) }, 3000)
                    else finishWithError(Code.ERR_NO_AD_FIELD, "배민 API 응답 없음")
                }
                r.startsWith("OK") -> finishWithSuccess("배민 광고 금액 ${targetAmount}원 변경")
                else -> finishWithError(Code.ERR_NO_AD_FIELD, "배민 API: $r")
            }
        }
    }

    // ───────── 쿠팡이츠 ─────────

    private fun handleCoupangPage(url: String) {
        when {
            url.contains("/login") || url.contains("/merchant/login") -> {
                if (state == State.WAITING_LOGIN_RESULT) {
                    handler.postDelayed({ detectCoupang2FA() }, 2000)
                    return
                }
                changeState(State.SUBMITTING_LOGIN)
                handler.postDelayed({ submitCoupangLogin() }, 2000)
            }
            url.contains("/verify") || url.contains("/otp") || url.contains("/2fa") -> {
                finishWithError(Code.ERR_LOGIN_FAILED, "쿠팡 2단계 인증 필요 — 자동화 불가")
            }
            state == State.WAITING_LOGIN_RESULT || state == State.SUBMITTING_LOGIN -> {
                log(Code.INFO_STATE, "쿠팡 로그인 성공 → store 페이지에서 API 직접 호출")
                changeState(State.PERFORMING_ACTION)
                handler.postDelayed({ callCoupangToggleApi() }, 2000)
            }
            state == State.LOADING_LOGIN -> {
                // 이미 로그인된 상태
                handler.postDelayed({
                    if (state == State.LOADING_LOGIN) {
                        val cur = webView?.url ?: ""
                        if (!cur.contains("/login")) {
                            log(Code.INFO_STATE, "쿠팡 이미 로그인됨 → API 직접 호출")
                            changeState(State.PERFORMING_ACTION)
                            callCoupangToggleApi()
                        }
                    }
                }, 4000)
            }
            else -> {}
        }
    }

    private fun detectCoupang2FA() {
        webView?.evaluateJavascript(
            "(function(){ var t = document.body ? document.body.innerText : ''; return (t.indexOf('인증')>=0 || t.indexOf('SMS')>=0 || t.indexOf('코드')>=0) ? '2FA' : 'NO'; })()"
        ) { result ->
            if (result?.contains("2FA") == true) {
                finishWithError(Code.ERR_LOGIN_FAILED, "쿠팡 SMS 인증 필요")
            }
        }
    }

    private fun submitCoupangLogin(retries: Int = MAX_RETRIES) {
        if (state != State.SUBMITTING_LOGIN) return
        val id = escapeForJs(AdManager.getCoupangId())
        val pw = escapeForJs(AdManager.getCoupangPw())

        val js = """
            (function() {
                var idInput = document.querySelector('input[type="text"], input[type="email"], input[name="username"], input[placeholder*="아이디"]');
                var pwInput = document.querySelector('input[type="password"]');
                if (!idInput || !pwInput) return 'NO_FORM|inputs=' + document.querySelectorAll('input').length;
                var s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                s.call(idInput, '$id'); idInput.dispatchEvent(new Event('input', {bubbles:true}));
                s.call(pwInput, '$pw'); pwInput.dispatchEvent(new Event('input', {bubbles:true}));
                setTimeout(function() {
                    var btn = document.querySelector('button[type="submit"]');
                    if (!btn) { var bs = document.querySelectorAll('button'); for (var i=0;i<bs.length;i++) { if (bs[i].textContent.indexOf('로그인')>=0) { btn=bs[i]; break; }}}
                    if (btn) btn.click(); else if (idInput.form) idInput.form.submit();
                }, 500);
                return 'OK';
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js) { result ->
            log(Code.INFO_JS_RESULT, "쿠팡 로그인: $result")
            if (result?.contains("NO_FORM") == true) {
                if (retries > 0) handler.postDelayed({ submitCoupangLogin(retries - 1) }, RETRY_DELAY)
                else finishWithError(Code.ERR_NO_LOGIN_FORM, "쿠팡 로그인 폼 없음: $result")
            } else changeState(State.WAITING_LOGIN_RESULT)
        }
    }

    /** 쿠팡: advertising API로 캠페인 토글 (DOM 대신 API 직접 호출) */
    private fun callCoupangToggleApi() {
        if (state != State.PERFORMING_ACTION) return
        val turnOn = currentAction == Action.COUPANG_AD_ON
        val onOff = if (turnOn) "켜기" else "끄기"

        val js = """
            (function() {
                window.__coupangApiResult = null;

                var AD_API = 'https://advertising.coupangeats.com';

                function apiCall(method, path, body, callback) {
                    var xhr = new XMLHttpRequest();
                    xhr.open(method, AD_API + path, true);
                    xhr.setRequestHeader('Content-Type', 'application/json');
                    xhr.setRequestHeader('Accept', 'application/json');
                    xhr.withCredentials = true;
                    xhr.onload = function() { callback(null, xhr.status, xhr.responseText); };
                    xhr.onerror = function() { callback('네트워크오류', 0, ''); };
                    xhr.send(body ? JSON.stringify(body) : null);
                }

                // 1단계: advertising API 인증
                apiCall('POST', '/api/v1/auth/login',
                    {deviceId:'NOT_USED', accessToken:'NOT_USED'},
                    function(err1, s1, r1) {
                    if (err1 || s1 !== 200) {
                        window.__coupangApiResult = 'ERR|인증실패|status=' + s1 + '|' + (r1||err1).substring(0, 200);
                        return;
                    }

                    // 2단계: 캠페인 목록 조회
                    var today = new Date();
                    var y = today.getFullYear();
                    var m = String(today.getMonth() + 1).padStart(2, '0');
                    var d = String(today.getDate()).padStart(2, '0');

                    apiCall('POST', '/api/v1/campaign/list',
                        {size:10, page:0, dateRange:{startDate:y+'-'+m+'-01', endDate:y+'-'+m+'-'+d}},
                        function(err2, s2, r2) {
                        if (err2 || s2 !== 200) {
                            window.__coupangApiResult = 'ERR|캠페인조회실패|status=' + s2 + '|' + (r2||err2).substring(0, 200);
                            return;
                        }
                        var data = JSON.parse(r2);
                        var campaigns = data.campaigns || data.content || [];
                        if (!Array.isArray(campaigns) || campaigns.length === 0) {
                            window.__coupangApiResult = 'ERR|캠페인없음|' + r2.substring(0, 200);
                            return;
                        }

                        var campaign = campaigns[0];
                        var campaignId = String(campaign.id || '');
                        var currentActive = campaign.isActive;
                        if (!campaignId) {
                            window.__coupangApiResult = 'ERR|캠페인ID없음';
                            return;
                        }

                        // 이미 원하는 상태이면 스킵
                        if (currentActive === $turnOn) {
                            window.__coupangApiResult = 'OK|이미${onOff}상태|id=' + campaignId;
                            return;
                        }

                        // 3단계: 토글 API 호출
                        apiCall('POST', '/api/v1/campaign/toggle',
                            {id: campaignId, isActive: $turnOn},
                            function(err3, s3, r3) {
                            if (err3 || s3 !== 200) {
                                window.__coupangApiResult = 'FAIL|토글실패|status=' + s3 + '|' + (r3||err3).substring(0, 200);
                                return;
                            }
                            var res = JSON.parse(r3);
                            window.__coupangApiResult = 'OK|id=' + campaignId + '|isActive=' + res.isActive;
                        });
                    });
                });

                return 'STARTED';
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js) { result ->
            log(Code.INFO_JS_RESULT, "쿠팡API시작: $result")
            handler.postDelayed({ pollCoupangApiResult(5) }, 3000)
        }
    }

    /** 쿠팡: API 비동기 결과 폴링 */
    private fun pollCoupangApiResult(retries: Int) {
        if (state != State.PERFORMING_ACTION) return
        val turnOn = currentAction == Action.COUPANG_AD_ON
        val onOff = if (turnOn) "켜기" else "끄기"

        webView?.evaluateJavascript(
            "(function(){ return window.__coupangApiResult || 'PENDING'; })()"
        ) { result ->
            val r = result?.removeSurrounding("\"") ?: "PENDING"
            log(Code.INFO_JS_RESULT, "쿠팡API폴링($retries): $r")
            when {
                r == "PENDING" -> {
                    if (retries > 0) handler.postDelayed({ pollCoupangApiResult(retries - 1) }, 3000)
                    else finishWithError(Code.ERR_NO_AD_FIELD, "쿠팡 API 응답 없음")
                }
                r.startsWith("OK") -> {
                    AdManager.setCoupangAdOn(turnOn)
                    finishWithSuccess("쿠팡 광고 $onOff 완료 ($r)")
                }
                else -> finishWithError(Code.ERR_NO_TOGGLE, "쿠팡 API: $r")
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
        return str.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")
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
