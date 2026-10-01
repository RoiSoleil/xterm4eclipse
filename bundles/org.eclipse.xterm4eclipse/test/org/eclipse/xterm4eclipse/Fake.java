package org.eclipse.xterm4eclipse;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A recording stand-in for an Eclipse interface, without a mocking library. */
final class Fake implements InvocationHandler {

	private final Map<String, Function<Object[], Object>> handlers = new HashMap<>();
	final List<String> calls = new CopyOnWriteArrayList<>();

	Fake on(String method, Function<Object[], Object> handler) {
		handlers.put(method, handler);
		return this;
	}

	<T> T as(Class<T> type) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, this));
	}

	int count(String method) {
		return (int) calls.stream().filter(method::equals).count();
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) {
		String name = method.getName();
		switch (name) {
		case "equals":
			return proxy == args[0];
		case "hashCode":
			return System.identityHashCode(proxy);
		case "toString":
			return "Fake@" + System.identityHashCode(proxy);
		default:
		}
		calls.add(name);
		Function<Object[], Object> handler = handlers.get(name);
		if (handler != null) {
			return handler.apply(args == null ? new Object[0] : args);
		}
		Class<?> type = method.getReturnType();
		if (type == boolean.class) {
			return false;
		}
		if (type == int.class) {
			return 0;
		}
		if (type == long.class) {
			return 0L;
		}
		return null;
	}
}
