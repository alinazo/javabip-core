/*
 * Copyright (c) 2012 Crossing-Tech TM Switzerland. All right reserved.
 * Copyright (c) 2012, RiSD Laboratory, EPFL, Switzerland.
 *
 * Author: Simon Bliudze, Alina Zolotukhina, Anastasia Mavridou, and Radoslaw Szymanek
 * Date: 10/15/12
 */

package org.bip.executor;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bip.api.BIPComponent;
import org.bip.api.BIPEngine;
import org.bip.api.Behaviour;
import org.bip.api.ComponentProvider;
import org.bip.api.Executor;
import org.bip.api.Port;
import org.bip.api.PortBase;
import org.bip.api.PortType;
import org.bip.exceptions.BIPException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * It is not a multi-thread safe executor kernel therefore it should never be directly used.
 * It needs to proxied to protect it from multi-thread access by for example Akka actor approach. 
 */
public class ExecutorKernel extends SpecificationParser implements Executor, ComponentProvider {

	String id;
	
	private BIPEngine engine;
	
	private ArrayList<String> notifiers;

	protected boolean registered = false;

	private Logger logger = LoggerFactory.getLogger(ExecutorKernel.class);
	
	private Map<String, Object> dataEvaluation = new Hashtable<String, Object>();
	
	boolean waitingForSpontaneous = false;
	
	Executor proxy;
	
	/**
	 * By default, the Executor is created for a component with annotations. If
	 * you want to create the Executor for a component with behaviour, use
	 * another constructor
	 * 
	 * @param bipComponent
	 * @throws BIPException
	 */
	public ExecutorKernel(Object bipComponent, String id) throws BIPException {
		this(bipComponent, id, true);
	}

	public ExecutorKernel(Object bipComponent, String id, boolean useSpec)
			throws BIPException {
		super(bipComponent, useSpec);
		this.id = id;
		this.notifiers = new ArrayList<String>();
	}
	
	public void setProxy(Executor proxy) {
		this.proxy = proxy;
	}

	public void register(BIPEngine engine) {
		if (proxy == null) {
			throw new BIPException("Proxy to provide multi-thread safety was not provided.");
		}
		this.engine = engine;
		registered = true;
		waitingForSpontaneous = false;
		proxy.step();
	}

	public void deregister() {
		this.registered = false;
		this.waitingForSpontaneous = false;
		this.engine = null;
	}

	/**
	 * 
	 * @return true if the next step can be immediately executed, false if a spontaneous event must happen to have reason to execute next step again.
	 * @throws BIPException
	 */
	public void step() throws BIPException {
		
		// if the actor was deregistered then it no longer does any steps.
		if (!registered)
			return;
		
		dataEvaluation.clear();
		// We need to compute this in order to be able to execute anything
		// TODO compute only guards needed for this current state
		// TODO first compute the guards only for internal transition

		Hashtable<String, Boolean> guardToValue = behaviour.computeGuardsWithoutData();

		// we have to compute this in order to be able to raise an exception
		boolean existInternal = behaviour.existEnabled(PortType.internal,guardToValue);
		
		if (existInternal) {
			logger.debug("About to execute internal transition for component {}", id);
			behaviour.executeInternal(guardToValue);
			logger.debug("Issuing next step message for component {}", id);
			// Scheduling the next execution step.
			proxy.step();
			logger.debug("Finishing current step that has executed an internal transition for component {}", id);
			return;
		};

		boolean existSpontaneous = behaviour.existEnabled(PortType.spontaneous, guardToValue);

		if (existSpontaneous && !notifiers.isEmpty()) {

			for (String port : notifiers) {
				if (behaviour.hasTransitionFromCurrentState(port)) {
					// TODO, BUG, what if the enabled transition was for spontaneous port 1, but here we picked up another 
					// spontaneous event? existSpontaneous maybe true to any transition, but we may have also some 
					// spontaneous events that are not supposed to be executed. 
					logger.debug("About to execute spontaneous transition {} for component {}", port, id);
					this.executeSpontaneous(port);
					notifiers.remove(port);
					logger.debug("Issuing next step message for component {}", id);
					// Scheduling the next execution step.					
					proxy.step();
					logger.debug("Finishing current step that has executed a spontaneous transition for component {}", id);
					return;
				}
			}

		}

		boolean existEnforceable = behaviour.existEnabled(PortType.enforceable, guardToValue);
		
		Set<Port> globallyDisabledPorts = behaviour.getGloballyDisabledPorts(guardToValue);

		if (existEnforceable) {
			logger.debug("About to execute engine inform for component {}", id);
			engine.inform(proxy, behaviour.getCurrentState(), globallyDisabledPorts);
			// Next step will be invoked upon finishing treatment of the message execute.
			return;
		} 
		
		// existSpontaneous transition exists but spontaneous event has not happened yet, thus a follow step should be postponned until
		// any spontaneous event is received.
		if (existSpontaneous) {
			logger.debug("Finishing current step for component {} doing nothing due no spontaneous events.", id);
			waitingForSpontaneous = true;
			// Next step will be invoked upon receiving a spontaneous event. 
			return;
		}
		
		throw new BIPException("No transition of known type from state "
					+ behaviour.getCurrentState() + " in component "
					+ this.getId());

	}

	private void executeSpontaneous(String portID) throws BIPException {

		if (portID == null || portID.isEmpty()) {
			return;
		}
		
		// execute spontaneous
		logger.info("Executing spontaneous transition {}.", portID);
		
		behaviour.execute(portID);

	}

	/**
	 * Executes a particular transition as told by the Engine
	 */
	public void execute(String portID) {

		// execute the particular transition
		// TODO: need to check that port is enforceable, do not allow
		// spontaneous executions here.
		// TODO: maybe we can then change the interface from String port to Port
		// port?
		if (dataEvaluation == null || dataEvaluation.isEmpty()) {
			behaviour.execute(portID);
		}
		else {
		// TODO, We need to check that we have all data required for the execution provided by the engine.
			behaviour.execute(portID, dataEvaluation);
		}

		logger.debug("Issuing next step message for component {}", id);
		proxy.step();
		
	}

	public void inform(String portID) {
		
		// TODO what if the port (spontaneous does not exist?). It should throw an exception or ignore it.
		if (portID == null || portID.isEmpty()) {
			return;
		}
		
		logger.info("{} was informed of a spontaneous transition {}", this.getId(), portID);

		notifiers.add(portID);
		
		if (waitingForSpontaneous) {
			logger.debug("Issuing next step message for component {}", id);
			waitingForSpontaneous = false;
			proxy.step();
		}
		
	}

	public <T> T getData(String name, Class<T> clazz) {

		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException(
					"The name of the required data variable from the component "
							+ bipComponent.getClass().getName()
							+ " cannot be null or empty.");
		}

		T result = null;

		try {
			logger.debug("Component {} getting data {}.",
					behaviour.getComponentType(), name);
			Object methodResult = behaviour.getDataOutMapping().get(name)
					.invoke(bipComponent);

			if (!methodResult.getClass().isAssignableFrom(clazz)) {
				result = getPrimitiveData(name, methodResult, clazz);
			} else
				result = clazz.cast(methodResult);

		} catch (IllegalAccessException e) {
			ExceptionHelper.printExceptionTrace(logger, e);
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			ExceptionHelper.printExceptionTrace(logger, e);
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			ExceptionHelper.printExceptionTrace(logger, e);
			ExceptionHelper.printExceptionTrace(logger, e.getTargetException());
			e.printStackTrace();
		}
		return result;
	}

	Set<Class<?>> primitiveTypes = new HashSet<Class<?>>(
			Arrays.<Class<?>> asList(int.class, float.class, double.class,
					byte.class, long.class, short.class, boolean.class,
					char.class));

	<T> T getPrimitiveData(String name, Object methodResult, Class<T> clazz) {

		if (primitiveTypes.contains(clazz)) {

			// For primitive types, as specified in primitiveTypes set,
			// we use direct casting that will employ autoboxing
			// feature from Java. Therefore, we suppress unchecked
			@SuppressWarnings("unchecked")
			T result = (T) methodResult;

			return result;
		} else
			throw new IllegalArgumentException("The type "
					+ methodResult.getClass()
					+ " of the required data variable " + name
					+ " from the component "
					+ bipComponent.getClass().getName()
					+ " does not correspond to the specified return type "
					+ clazz);

	}

	public List<Boolean> checkEnabledness(PortBase port,
										  List<Map<String, Object>> data) {
		try {
			return behaviour.checkEnabledness(port.getId(), data);
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		} catch (BIPException e) {
			e.printStackTrace();
		}
		return null;
	}

	public BIPComponent component() {
		if (proxy == null) {
			throw new BIPException("Proxy to provide multi-thread safety was not provided.");
		}
		return proxy;
	}

	public void setData(String dataName, Object data) {
		this.dataEvaluation.put(dataName, data);
	}

	public String toString() {
		StringBuilder result = new StringBuilder();

		result.append("BIPComponent=(");
		result.append("type = " + behaviour.getComponentType());
		result.append(", hashCode = " + this.hashCode());
		result.append(")");

		return result.toString();

	}

	public String getType() {
		return behaviour.getComponentType();
	}

	public Behaviour getBehavior() {
		return behaviour;
	}
	
	@Override
	public String getId() {
		return id;
	}

	@Override
	public BIPEngine engine() {
		return engine;
	}

}