/*
 * Copyright (c) 2012 Crossing-Tech TM Switzerland. All right reserved.
 * Copyright (c) 2012, RiSD Laboratory, EPFL, Switzerland.
 *
 * Author: Simon Bliudze, Alina Zolotukhina, Anastasia Mavridou, and Radoslaw Szymanek
 * Date: 01/27/14
 */
package org.bip.api;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;


public interface ExecutableTransition {

	public Method method();

	public String guard();

	public Iterable<Data<?>> dataRequired();

	public boolean hasDataOnGuards();

	public boolean guardIsTrue(Map<String, Boolean> guardToValue);
	
	public Port.Type getType();
	
	public String name() ;

	public String source();

	public String target() ;

	public boolean hasGuard();

	public Collection<Guard> transitionGuards();
}
