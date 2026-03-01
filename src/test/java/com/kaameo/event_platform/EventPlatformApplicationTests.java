package com.kaameo.event_platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
	"spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2,org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@Import(TestContainersConfiguration.class)
class EventPlatformApplicationTests {

	@Test
	void contextLoads() {
	}

}
