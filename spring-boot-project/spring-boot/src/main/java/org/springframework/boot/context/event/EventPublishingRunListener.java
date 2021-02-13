/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context.event;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ErrorHandler;

/**
 * {@link SpringApplicationRunListener} to publish {@link SpringApplicationEvent}s.
 * <p>
 * Uses an internal {@link ApplicationEventMulticaster} for the events that are fired
 * before the context is actually refreshed.
 *
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @author Andy Wilkinson
 * @author Artsiom Yudovin
 * @author Brian Clozel
 * @since 1.0.0
 *
 * EventPublishingRunListener：看这个名字我们就可以猜测出在这个整个EventPublishingRunListener就是帮助我们去驱动我们之前加载的ApplicationListener的一些EventPublishing的操作，
 * 因为我们的ApplicationListener是需要接收一些Event才能触发void onApplicationEvent(E event)的回调，谁去触发这样的方式呢？
 * 本质上说就是靠EventPublishingRunListener去触发的。
 */
public class EventPublishingRunListener implements SpringApplicationRunListener, Ordered {

	private final SpringApplication application;

	private final String[] args;

	private final SimpleApplicationEventMulticaster initialMulticaster;

	/**
	 * 这些Listener是怎么被拿出来的呢？
	 *
	 * 在EventPublishingRunListener构造函数把SpringApplication注入进来，
	 * 在这里new SimpleApplicationEventMulticaster()也就是我们用来做multicastEvent这个类，
	 * 并且将SpringApplication中所有的Listener给它注入到了我们的initialMulticaster当中，
	 * 自然他就可以找到我们所有的ApplicationListener
	 *
	 * @param application
	 * @param args
	 */
	public EventPublishingRunListener(SpringApplication application, String[] args) {
		this.application = application;
		this.args = args;
		this.initialMulticaster = new SimpleApplicationEventMulticaster();
		for (ApplicationListener<?> listener : application.getListeners()) {
			this.initialMulticaster.addApplicationListener(listener);
		}
	}

	@Override
	public int getOrder() {
		return 0;
	}

	/**
	 * 这个staring其实就是做了EventPublishingRunListener的starting方法，本质上来说是一个包装器
	 * @param bootstrapContext the bootstrap context
	 */
	@Override
	public void starting(ConfigurableBootstrapContext bootstrapContext) {
		// 这个包装器其实就是做了一个multicastEvent，并且multicast一个ApplicationStartingEvent，告诉我们所有的listener说我们现在触发了一个事件，叫做ApplicationStartingEvent
		this.initialMulticaster
				// 1）我们试着找一下有哪些对应事件的响应可以响应这个ApplicationStartingEvent
				// 2）进入multicastEvent方法，传入需要的发布事件，获取到相应的ApplicationListener，最后通过listener.onApplicationEvent(event)监听事件
				// 3）进入getApplicationListeners(event, type)，再进入retrieveApplicationListeners(eventType, sourceType, retriever)，
				// 可以看到并不是无脑的将SpringApplication中加载的10多个对应的Listener全部返回出去，
				// 做挨个的调用，而是会加一个判断supportsEvent(listener, eventType, sourceType)，
				// 判断一下Listener是不是支持我们要发的这个事件
				// 4）最后调用对应的ApplicationListener中的onApplicationEvent方法，至此SpringBoot启动过程的starting阶段就完成了
				.multicastEvent(new ApplicationStartingEvent(bootstrapContext, this.application, this.args));
	}

	@Override
	public void environmentPrepared(ConfigurableBootstrapContext bootstrapContext,
			ConfigurableEnvironment environment) {
		this.initialMulticaster.multicastEvent(
				new ApplicationEnvironmentPreparedEvent(bootstrapContext, this.application, this.args, environment));
	}

	@Override
	public void contextPrepared(ConfigurableApplicationContext context) {
		this.initialMulticaster
				// 发送ApplicationContextInitializedEvent，告诉监听器ContextInitialize已经完成了
				.multicastEvent(new ApplicationContextInitializedEvent(this.application, this.args, context));
	}

	@Override
	public void contextLoaded(ConfigurableApplicationContext context) {
		for (ApplicationListener<?> listener : this.application.getListeners()) {
			if (listener instanceof ApplicationContextAware) {
				((ApplicationContextAware) listener).setApplicationContext(context);
			}
			context.addApplicationListener(listener);
		}
		this.initialMulticaster.multicastEvent(new ApplicationPreparedEvent(this.application, this.args, context));
	}

	@Override
	public void started(ConfigurableApplicationContext context) {
		context.publishEvent(new ApplicationStartedEvent(this.application, this.args, context));
		AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
	}

	@Override
	public void running(ConfigurableApplicationContext context) {
		context.publishEvent(new ApplicationReadyEvent(this.application, this.args, context));
		AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
	}

	@Override
	public void failed(ConfigurableApplicationContext context, Throwable exception) {
		ApplicationFailedEvent event = new ApplicationFailedEvent(this.application, this.args, context, exception);
		if (context != null && context.isActive()) {
			// Listeners have been registered to the application context so we should
			// use it at this point if we can
			context.publishEvent(event);
		}
		else {
			// An inactive context may not have a multicaster so we use our multicaster to
			// call all of the context's listeners instead
			if (context instanceof AbstractApplicationContext) {
				for (ApplicationListener<?> listener : ((AbstractApplicationContext) context)
						.getApplicationListeners()) {
					this.initialMulticaster.addApplicationListener(listener);
				}
			}
			this.initialMulticaster.setErrorHandler(new LoggingErrorHandler());
			this.initialMulticaster.multicastEvent(event);
		}
	}

	private static class LoggingErrorHandler implements ErrorHandler {

		private static final Log logger = LogFactory.getLog(EventPublishingRunListener.class);

		@Override
		public void handleError(Throwable throwable) {
			logger.warn("Error calling ApplicationEventListener", throwable);
		}

	}

}
