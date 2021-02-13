package sample.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DebuggingBootApplication
 *
 * 其实引入SpringBoot包的过程本质上来说就是往我们的ClassPath下面去放入我们spring.factories这样的文件的过程，
 * 这样在整个Spring容器启动过程中就会自主的将spring.factories这样的一些类加载到它的内存当中，以便后续去做初始化和事件监听，执行它们想要的操作。
 *
 * 整个SpringBoot的启动流程本质上来说是通过对listener的不同状态的维护以及做对应的通知，
 * 带动我们所有的ApplicationContextInitializer做初始化 并且 做ApplicationListener的不同事件的监听。
 *
 * @author zhanglei.
 * @date 2021/2/10 9:09.
 */
@SpringBootApplication
public class DebuggingSpringBootApplication {

	public static void main(String[] args) {
		SpringApplication.run(DeploymentTestApplication.class, args);
	}

}
