# Coupang Eats Advertising API (CMG)

ApiSniff v1.3으로 2026-02-16에 캡처.
쿠팡이츠 스토어 포털(store.coupangeats.com) 내 광고관리 모듈(advertising.coupangeats.com)의 API.

## Base URL
```
https://advertising.coupangeats.com
```

## 인증 방식
- store.coupangeats.com 로그인 후 쿠키 기반 인증
- advertising API 별도 세션 초기화 필요 (아래 auth/login)

---

## 1. POST /api/v1/auth/login
광고 API 세션 초기화. store 로그인 쿠키로 자동 인증됨.

**Request:**
```json
{
  "deviceId": "NOT_USED",
  "accessToken": "NOT_USED"
}
```

**Response (200):**
```json
{
  "coupayAccountId": "370871",
  "name": "이원규",
  "merchantAccountType": "MERCHANT",
  "type": "MERCHANT",
  "stores": [
    {
      "storeId": "756378",
      "paymentStoreId": "194509659802",
      "name": "BBQ 이천하이닉스점",
      "approvalStatus": "APP_DISPLAY",
      "ownerCoupayAccountId": "370871",
      "managedStoreType": "FRANCHISE"
    }
  ],
  "merchantId": "345984",
  "advertiserId": "183655",
  "config": {},
  "clientIp": "118.235.10.90",
  "cmgDeviceId": "31fc1df6-01c6-49a8-8266-9aed13679712"
}
```

---

## 2. POST /api/v1/campaign/list
캠페인 목록 조회.

**Request:**
```json
{
  "size": 10,
  "page": 0,
  "dateRange": {
    "startDate": "2026-02-10",
    "endDate": "2026-02-16"
  }
}
```

**Response (200):**
```json
{
  "campaigns": [
    {
      "adType": "TARGET_AD",
      "id": "406386",
      "adAccountId": "183655",
      "adStore": {
        "id": "201006",
        "storeId": "756378",
        "isSuspended": false,
        "isTerminated": false
      },
      "operationPeriod": {
        "isUnending": true,
        "startDate": "2025-12-25T15:00:00.000Z",
        "endDate": null
      },
      "budgetSetting": {
        "budgetType": "DAILY",
        "budget": 50000,
        "budgetMoney": { "currencyCode": "KRW", "units": 50000, "nanos": 0 }
      },
      "bidPricing": {
        "pricingType": "CPS",
        "pricingRate": 5,
        "newCustomerPricingRate": 10
      },
      "isActive": true,
      "isDeleted": false,
      "revenue": 4243000
    }
  ],
  "page": {
    "totalElements": 1,
    "totalPages": 1,
    "hasNextPage": false
  }
}
```

---

## 3. POST /api/v1/campaign/toggle
캠페인 ON/OFF 토글.

**Request:**
```json
{"id": "406386", "isActive": false}
```
- `id`: 캠페인 ID (String)
- `isActive`: true=켜기, false=끄기

**Response (200):**
토글 후 캠페인 전체 정보 반환 (campaign/list의 개별 캠페인과 동일 구조)
```json
{
  "adType": "TARGET_AD",
  "id": "406386",
  "adAccountId": "183655",
  "adStore": { "id": "201006", "storeId": "756378" },
  "isActive": false,
  "budgetSetting": { "budgetType": "DAILY", "budget": 50000 },
  "bidPricing": { "pricingType": "CPS", "pricingRate": 5, "newCustomerPricingRate": 10 }
}
```

---

## 4. GET /api/v1/user/stores
사용자 매장 정보 조회.

**Response (200):**
```json
{
  "stores": [
    {
      "storeId": "756378",
      "paymentStoreId": "194509659802",
      "name": "BBQ 이천하이닉스점",
      "approvalStatus": "APP_DISPLAY",
      "ownerCoupayAccountId": "370871",
      "managedStoreType": "FRANCHISE",
      "isRegistered": true,
      "wowStatus": "WOW",
      "address": "경기도 이천시 부발읍 경충대로 2102"
    }
  ]
}
```

---

## 핵심 ID 정리
| 항목 | 값 |
|------|------|
| Campaign ID | 406386 |
| Store ID | 756378 |
| Ad Account ID | 183655 |
| Ad Store ID | 201006 |
| Coupay Account ID | 370871 |
| Merchant ID | 345984 |

## 기술 노트
- wujie 마이크로프론트엔드: store.coupangeats.com 내 Web Components + iframe으로 로드
- 모든 API는 XMLHttpRequest 사용 (fetch 아님)
- CORS: Origin: https://store.coupangeats.com, Referer: https://store.coupangeats.com/
- Headers: `Accept: application/json`, `Content-Type: application/json`
- OFF 토글 시 UI에서 확인 다이얼로그 표시됨 (API 직접 호출 시 불필요)
- 광고 타입: TARGET_AD, CPS 5%, 신규고객 10%, 일예산 50,000원
