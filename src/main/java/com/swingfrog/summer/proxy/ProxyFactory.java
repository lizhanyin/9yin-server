package com.swingfrog.summer.proxy;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import net.bytebuddy.matcher.ElementMatchers;

public class ProxyFactory {

	@SuppressWarnings("unchecked")
	public static <T> T getProxyInstance(Object target, ProxyMethodInterceptor interceptor) {
		try {
			return (T) new ByteBuddy()
					.subclass(target.getClass())
					.method(ElementMatchers.isVirtual()
							.and(ElementMatchers.not(ElementMatchers.isFinal())))
					.intercept(InvocationHandlerAdapter.of(
							(proxy, method, args) -> interceptor.intercept(target, method, args)))
					.make()
					.load(target.getClass().getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
					.getLoaded()
					.getDeclaredConstructor()
					.newInstance();
		} catch (RuntimeException | ReflectiveOperationException e) {
			throw new RuntimeException(String.format("create proxy instance failure, class[%s]",
					target.getClass().getName()), e);
		}
	}

}
