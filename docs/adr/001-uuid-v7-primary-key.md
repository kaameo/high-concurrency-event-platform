# ADR-001: UUID v7을 Primary Key 전략으로 채택

- **Status**: Accepted
- **Date**: 2026-02-28
- **Deciders**: 개발팀

## Context

선착순 쿠폰 발급 시스템의 엔티티(`coupon_event`, `coupon_issue`) PK 전략을 결정해야 한다. 고동시성 환경에서 분산 생성이 가능하면서도 데이터베이스 성능을 유지하는 PK가 필요하다.

### 요구사항
- 분산 환경에서 충돌 없이 ID 생성 가능
- PostgreSQL B-tree 인덱스 성능 최적화 (순차 삽입)
- 시간 순서 정렬 가능
- 외부 노출 시 예측 불가능

## Decision Drivers

- PostgreSQL 네이티브 타입 지원 여부 (저장 효율, 인덱스 크기)
- B-tree 인덱스 삽입 패턴 (순차 vs 랜덤)
- JPA/Hibernate 네이티브 타입 매핑
- 국제 표준 준수 여부
- Java 생태계 라이브러리 성숙도

## Considered Options

### Option 1: BIGSERIAL (Auto-Increment)
- 순차 삽입으로 B-tree 최적
- 단일 DB 의존, 분산 생성 불가
- ID 예측 가능 (보안 취약)

### Option 2: UUID v4 (Random)
- 분산 생성 가능, PostgreSQL `uuid` 네이티브
- 완전 랜덤으로 B-tree 페이지 스플릿 빈번 → 인덱스 성능 저하
- 시간 순 정렬 불가

### Option 3: ULID (char(26))
- 시간 순 정렬, 분산 생성, monotonic
- PostgreSQL 네이티브 타입 없음 → `char(26)` 저장 (26 bytes)
- 인덱스 크기 `uuid` 대비 62% 더 큼
- 콜레이션 기반 문자열 비교 (바이너리 대비 느림)
- JPA `String` 타입 (타입 안전성 상실)

### Option 4: UUID v7 (Time-Ordered) ✅ 선택

- RFC 9562 국제 표준 (IETF)
- PostgreSQL `uuid` 네이티브 타입 (16 bytes)
- 48-bit 밀리초 타임스탬프 → 시간 순 정렬, 순차 삽입
- monotonic random (동일 밀리초 내 순서 보장)
- JPA `UUID` 타입 네이티브 지원
- 바이너리 비교 (콜레이션 오버헤드 없음)

## Decision

**UUID v7을 모든 엔티티의 PK로 채택한다.**

- Java 라이브러리: `com.github.f4b6a3:uuid-creator:6.0.0`
- 생성 메서드: `UuidCreator.getTimeOrderedEpoch()` (RFC 9562 Section 5.7)
- 애플리케이션 사이드 생성 (`@PrePersist` 또는 생성자)
- PostgreSQL 컬럼 타입: `UUID`
- JPA 매핑: `@Id @Column(columnDefinition = "uuid") private UUID id;`

## Consequences

### Positive
- 16 bytes 네이티브 저장 → 인덱스 크기 최적
- B-tree 순차 삽입 → 페이지 스플릿 최소화
- 분산 환경 대비 완료 (DB 시퀀스 의존 제거)
- RFC 표준 → 장기적 생태계 호환성
- JPA UUID 타입 → 컴파일 타임 타입 안전성
- FK JOIN 시 16바이트 바이너리 비교 → 성능 우수

### Negative
- 문자열 표현 36자 (ULID 26자 대비 길음, 로그/디버깅 시)
- 애플리케이션 사이드 생성 → DB 함수(`gen_random_uuid()`)와 불일치
- 외부 라이브러리 의존 (`uuid-creator`)

### Risks
- `uuid-creator` 라이브러리 유지보수 중단 시 대체 필요 → JDK 향후 버전에서 UUID v7 네이티브 지원 가능성

## Decision History

1. **초기 설계**: PRD에서 `BIGSERIAL` PK로 명시
2. **1차 전환**: 분산 환경 대비를 위해 ULID (`char(26)`)로 변경
3. **2차 전환**: PostgreSQL 성능 분석 후 UUID v7 (`uuid` 네이티브)으로 최종 확정

## References

- [RFC 9562 - Universally Unique IDentifiers (UUIDs)](https://www.rfc-editor.org/rfc/rfc9562)
- [uuid-creator GitHub](https://github.com/f4b6a3/uuid-creator)
- [PostgreSQL UUID Type](https://www.postgresql.org/docs/16/datatype-uuid.html)
