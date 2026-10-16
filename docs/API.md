# ReBalance API v1

All endpoints are under `/v1`. The SPA and the API are served from the same origin in production. In local dev, CRA's `setupProxy.js` forwards `/v1` (including the WebSocket) to `http://localhost:8080`.

## Conventions

- **Auth:** send `Authorization: Bearer <token>` (JWT, valid for 30 days). Every endpoint needs it except those marked **public**.
- **Money:** JSON numbers with at most 2 decimals, e.g. `12.5` or `12.50`. All money in a group uses the group's currency.
- **Dates:** expense dates are `"YYYY-MM-DD"`. Timestamps are ISO-8601 UTC, e.g. `"2026-10-15T12:34:56Z"`.
- **Paging:** paged lists return
  ```json
  { "content": [...], "page": 0, "size": 20, "totalElements": 57, "totalPages": 3 }
  ```
  `size` is capped at 100.
- **Errors:** errors use RFC 7807 `application/problem+json`:
  ```json
  { "type": "about:blank", "title": "RB_102", "status": 409, "detail": "Expense was changed by someone else. Reload and try again." }
  ```
  `title` is a stable error code; `detail` is a message you can show to the user.

  | Status | Meaning |
  |---|---|
  | 400 | Validation error (`RB_998`; `detail` lists the problems), bad split, wrong current password, … |
  | 401 | Not authenticated: token missing, invalid, expired or revoked (`RB_401`); wrong email/password on login (`RB_402`). The client should log out on 401, **except** for the login/register/change-password calls themselves. |
  | 403 | Logged in, but not allowed, e.g. a member who isn't the owner (`RB_202`). |
  | 404 | Not found, **or not a member** (the API doesn't reveal whether a group exists). |
  | 409 | Conflict: stale `version` (`RB_102`), email taken (`RB_001`), leaving with a non-zero balance (`RB_203`), … |
  | 413 | Image too large (`RB_304`). |
  | 429 | Too many attempts (`RB_004`); retry later. |

## Types

```ts
type GroupResponse = {
  id: number; name: string; currency: string;   // ISO 4217, e.g. "PLN"
  favorite: boolean;                             // my own flag
  owner: boolean;                                // am I the owner?
  ownerUserId: number;
  myMemberId: number;
  myBalance: number;                             // + = others owe me, - = I owe
  memberCount: number;                           // ACTIVE members
  inviteToken: string | null;                    // visible to every member
  inviteEnabled: boolean;
  createdAt: string;
}

type MemberResponse = {
  memberId: number; userId: number; nickname: string;   // no emails, ever
  status: "ACTIVE" | "LEFT" | "REMOVED";
  owner: boolean;
  balance: number;
  joinedAt: string;
}

type DebtSettlementResponse = {                  // "debtor pays creditor amount"
  debtorMemberId: number; debtorUserId: number; debtorNickname: string;
  creditorMemberId: number; creditorUserId: number; creditorNickname: string;
  amount: number;
}

type MemberRef = { memberId: number; userId: number; nickname: string };

type ExpenseShareResponse = MemberRef & {
  weight: number | null;   // SHARES: the weight; EQUAL: 1; AMOUNTS: null
  amount: number;          // exact share in cents precision; shares sum to the expense amount
};

type GroupExpenseResponse = {
  id: number; groupId: number;
  type: "EXPENSE" | "TRANSFER";
  splitType: "EQUAL" | "SHARES" | "AMOUNTS";
  description: string; amount: number; date: string;   // YYYY-MM-DD
  category: string | null;
  payer: MemberRef;
  shares: ExpenseShareResponse[];
  createdBy: { userId: number; nickname: string };
  createdAt: string; updatedAt: string;
  version: number;          // send it back on edit and delete
  hasImage: boolean;
}

type PersonalExpenseResponse = {
  id: number; amount: number; description: string; date: string;
  category: string | null; version: number; hasImage: boolean;
}

type CategoryResponse = { id: number; name: string };
type SumByCategoryResponse = { category: string; sum: number };

type NotificationResponse = {
  id: number;
  type: "MemberJoined" | "MemberLeft" | "MemberRemoved" | "GroupRenamed" | "GroupDeleted"
      | "GroupExpenseAdded" | "GroupExpenseEdited" | "GroupExpenseDeleted" | "SettlementAdded";
  groupId: number | null; expenseId: number | null;
  actorUserId: number; actorNickname: string;
  message: string;          // ready to display, e.g. 'Alice added "Dinner" (45.00 PLN) in Trip'
  createdAt: string;
}
```

## Account

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/user/register` **public, rate-limited** | `{email, password, nickname, currency}` | `201 {id, nickname, email, currency, personalGroupId, token}` |
| POST | `/user/login` **public, rate-limited** | `{email, password}` | `200 {token}`; `401 RB_402` on bad credentials |
| POST | `/user/logout` | — | `204` (revokes this token) |
| GET | `/user/info` | — | `{id, nickname, email, currency, personalGroupId}` |
| PUT | `/user/password` | `{currentPassword, newPassword}` | `204`; revokes all **other** tokens; `400 RB_003` if the current password is wrong |
| GET | `/user/groups` | — | `GroupResponse[]` (my active, non-personal groups) |

- **Password rule:** 8–255 characters, with at least one digit, one lowercase letter, one uppercase letter and one special character.
- **Nickname:** 1–64 characters.
- **Email:** stored in lowercase.

## Groups

"Member" means an ACTIVE member. "Owner" means the group's owner, which is the creator unless ownership was transferred.

| Method | Path | Who | Body | Response |
|---|---|---|---|---|
| POST | `/group` | any | `{name, currency}` | `201 GroupResponse` (I become owner and member; an invite token is generated) |
| GET | `/group/{g}` | member | — | `GroupResponse` |
| PATCH | `/group/{g}` | member | `{name}` | `GroupResponse` |
| DELETE | `/group/{g}` | owner | — | `204` (soft delete; disappears for everyone) |
| PUT | `/group/{g}/favorite` | member | `{favorite: boolean}` | `204` |
| GET | `/group/{g}/members` | member | — | `MemberResponse[]` (includes LEFT/REMOVED, so history can show names) |
| POST | `/group/{g}/leave` | member | — | `204`. `409 RB_203` if my balance ≠ 0. If the owner leaves, ownership passes to the longest-standing ACTIVE member. When the last member leaves, the group is soft-deleted. |
| DELETE | `/group/{g}/members/{memberId}` | owner | — | `204`. `409 RB_203` if that member's balance ≠ 0. The owner can't remove themselves (use leave). |
| GET | `/group/{g}/debt-settlement` | member | — | `DebtSettlementResponse[]` (at most n−1 transfers) |
| POST | `/group/{g}/settlements` | member | `{debtorMemberId, creditorMemberId, amount, date?}` | `201 GroupExpenseResponse` (a TRANSFER: the debtor pays, the creditor is the only share) |
| POST | `/group/{g}/invite/rotate` | owner | — | `GroupResponse` with a new token; old links stop working |
| PUT | `/group/{g}/invite` | owner | `{enabled: boolean}` | `GroupResponse` |

Group name: 1–100 characters. Currency: 3 uppercase letters.

## Invites

The invite URL the frontend shares is `https://<site>/join/<inviteToken>`.

| Method | Path | Who | Response |
|---|---|---|---|
| GET | `/invites/{token}` | **public**, rate-limited; the token is optional | `{groupId, name, currency, memberCount, alreadyMember}`. `alreadyMember` is true only if a valid token was sent and I'm an ACTIVE member. `404 RB_206` if the token is unknown or disabled. |
| POST | `/invites/{token}/join` | logged in | `GroupResponse`. Idempotent: a member who LEFT or was REMOVED is reactivated. |

## Group expenses (member only)

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/group/{g}/expenses?page=0&size=20` | — | `Page<GroupExpenseResponse>`, newest first (by date, then id) |
| GET | `/group/{g}/expenses/{e}` | — | `GroupExpenseResponse` |
| POST | `/group/{g}/expenses` | `ExpenseRequest` | `201 GroupExpenseResponse` |
| PUT | `/group/{g}/expenses/{e}` | `ExpenseRequest & {version}` | `GroupExpenseResponse`; `409 RB_102` if `version` is stale |
| DELETE | `/group/{g}/expenses/{e}?version=N` | — | `204` (soft delete); `409 RB_102` if stale |
| GET | `/group/{g}/categories` | — | `CategoryResponse[]`, most recently used first |
| GET | `/group/{g}/sum-by-category` | — | `SumByCategoryResponse[]` (EXPENSE only, transfers excluded) |

```ts
type ExpenseRequest = {
  type?: "EXPENSE" | "TRANSFER";            // default EXPENSE
  splitType: "EQUAL" | "SHARES" | "AMOUNTS";
  description: string;                      // 1–255 chars
  amount: number;                           // > 0, max 2 decimals
  date?: string;                            // YYYY-MM-DD, default today (UTC)
  category?: string | null;                 // free text, 1–64 chars; created per group on first use
  payerMemberId: number;
  shares: { memberId: number; weight?: number; amount?: number }[];
  version?: number;                         // required on PUT
}
```

**Split rules** (the server computes and stores each share's exact cents):
- **EQUAL:** list the participating `memberId`s; weights are ignored. The amount is divided equally.
- **SHARES:** `weight` is an integer from 1 to 1000 for each participant.
- **AMOUNTS:** `amount` (≥ 0) for each participant; the amounts must add up exactly to `amount`.
- **Leftover cents:** for EQUAL and SHARES, the largest-remainder rule hands out the leftover cents, ties broken by lower `memberId`. So shares always add up exactly to the total.
- **TRANSFER:** exactly one share, whose member differs from the payer. It takes the whole amount.
- **Participants:** the payer and every participant must be ACTIVE members of the group, and no member may appear twice.

**Balance:** balance = Σ(amounts paid) − Σ(own shares), over non-deleted expenses. Positive means the others owe this member.

## Personal expenses (owner only)

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/personal/expenses?page=0&size=20` | — | `Page<PersonalExpenseResponse>` |
| POST | `/personal/expenses` | `{amount, description, category?, date?}` | `201 PersonalExpenseResponse` |
| PUT | `/personal/expenses/{e}` | `{amount, description, category?, date?, version}` | `PersonalExpenseResponse` |
| DELETE | `/personal/expenses/{e}?version=N` | — | `204` |
| GET | `/personal/categories` | — | `CategoryResponse[]` |
| GET | `/personal/sum-by-category` | — | `SumByCategoryResponse[]` |

## Receipt images (anyone who can see the expense)

| Method | Path | Body | Response |
|---|---|---|---|
| PUT | `/expenses/{e}/image` | multipart, field `file` (JPEG/PNG/GIF, ≤ 5 MB; the web client converts photos to JPEG first) | `204`. The image is re-encoded to JPEG (≤ 2048 px), which strips EXIF/GPS data, and a 160 px thumbnail is made. |
| GET | `/expenses/{e}/image` | — | `image/jpeg` bytes. Fetch it with the Authorization header and show it via a blob URL. |
| DELETE | `/expenses/{e}/image` | — | `204` |
| POST | `/images/thumbnails` | `{expenseIds: number[]}` (max 100) | `[{expenseId, base64}]` JPEG thumbnails; inaccessible ids and ids without an image are left out |

## Notifications

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/notifications/unseen` | — | `NotificationResponse[]`, newest first, max 50 |
| POST | `/notifications/seen` | `{ids: number[]}` | `204` |

- **Recipients:** all ACTIVE members of the group except the person who did the action.
- **When sent:** only after the change is committed, so it is safe to refetch as soon as a notification arrives.

**WebSocket (STOMP):**
- **Connect:** `ws(s)://<same host>/v1/ws`, with the STOMP CONNECT header `Authorization: Bearer <token>`. Without a valid token, the CONNECT is rejected.
- **Subscribe:** `/user/queue/notifications`. Each message is one `NotificationResponse`.
- **Refetch hint:** use `groupId` to invalidate that group's data.

## Health

`GET /connect/test` (**public**) returns `200` with an empty body.
