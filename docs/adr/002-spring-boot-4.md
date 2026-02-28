# ADR-002: Spring Boot 4.x 채택 및 마이그레이션 전략

- **Status**: Accepted
- **Date**: 2026-02-28
- **Deciders**: 개발팀

## Context

프로젝트 시작 시점(2026-02)에 Spring Boot 4.0.3이 최신 GA 버전이다. Spring Boot 4는 대규모 모듈화(Modularization)를 단행하여 기존 3.x의 `spring-boot-autoconfigure` 모놀리식 jar를 기술별 모듈로 분리했다. 이로 인해 다수의 서드파티 라이브러리에서 호환성 이슈가 발생한다.

### 핵심 변경점
- `spring-boot-autoconfigure` → 기술별 개별 모듈 (`spring-boot-flyway`, `spring-boot-data-redis` 등)
- 테스트 어노테이션 패키지 이동 (`boot.test.autoconfigure.web.servlet` → `boot.webmvc.test.autoconfigure`)
- Jackson auto-configuration이 `@WebMvcTest` 슬라이스에 미포함

## Decision Drivers

- Java 21 Virtual Threads 활용 (고동시성 시스템 핵심)
- Spring Framework 7.x의 성능 개선
- 장기 지원(LTS) 및 보안 패치 수혜
- 서드파티 라이브러리 호환성 리스크

## Considered Options

### Option 1: Spring Boot 3.4.x (안정적)
- 모든 서드파티 라이브러리 호환
- Virtual Threads 지원 (3.2+)
- 향후 4.x 마이그레이션 비용 발생

### Option 2: Spring Boot 4.0.3 ✅ 선택
- 최신 모듈화 아키텍처
- Virtual Threads 개선 지원
- 서드파티 호환성 이슈 존재 (해결 가능)

## Decision

**Spring Boot 4.0.3을 채택하고, 호환성 이슈를 즉시 해결한다.**

### 호환성 해결 매트릭스

| 라이브러리 | 이슈 | 해결 |
|-----------|------|------|
| Flyway | auto-config 모듈 분리 | `flyway-core` → `spring-boot-flyway` starter |
| Redisson | `RedisAutoConfiguration` 패키지 변경 | 3.27.0 → **4.0.0** |
| Springdoc | Spring Framework 7.x 내부 API 변경 | 2.4.0 → **3.0.0** |
| QueryDSL AP | AP 클래스패스에 JPA API 누락 | `jakarta.persistence-api` AP에 명시 추가 |
| `@WebMvcTest` | 패키지 이동 | `boot.webmvc.test.autoconfigure` 사용 |
| `ObjectMapper` | `@WebMvcTest` 슬라이스 미포함 | 수동 인스턴스 생성 |

## Consequences

### Positive
- 최신 Spring Framework 7.x 성능/보안 개선 즉시 수혜
- 모듈화로 인한 빌드 시간 및 메모리 효율 개선
- Virtual Threads 최적화된 런타임
- 향후 마이그레이션 비용 제거

### Negative
- 서드파티 호환성 검증 비용 (초기 1회)
- 일부 라이브러리 최신 버전 강제 (Redisson 4.0, Springdoc 3.0)
- Spring Boot 4 관련 커뮤니티 자료 상대적으로 적음
- `@WebMvcTest` 슬라이스 동작 차이로 테스트 코드 수정 필요

### Risks
- Spring Boot 4 초기 버전 버그 가능성 → 마이너 버전 업데이트로 대응
- 추가 서드파티 라이브러리 도입 시 SB4 호환성 확인 필요

## References

- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Boot 4 Modularization](https://www.danvega.dev/blog/2025/12/12/spring-boot-4-modularization)
- 상세 트러블슈팅: [phase1-troubleshooting.md](../reports/phase1-troubleshooting.md)
