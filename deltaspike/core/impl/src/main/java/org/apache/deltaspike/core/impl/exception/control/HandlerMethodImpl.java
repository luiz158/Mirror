/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.deltaspike.core.impl.exception.control;

import org.apache.deltaspike.core.api.exception.control.annotation.BeforeHandles;
import org.apache.deltaspike.core.api.exception.control.annotation.Handles;
import org.apache.deltaspike.core.api.exception.control.event.ExceptionEvent;
import org.apache.deltaspike.core.api.exception.control.HandlerMethod;
import org.apache.deltaspike.core.api.literal.AnyLiteral;
import org.apache.deltaspike.core.util.metadata.builder.ImmutableInjectionPoint;
import org.apache.deltaspike.core.util.metadata.builder.InjectableMethod;
import org.apache.deltaspike.core.api.provider.BeanManagerProvider;
import org.apache.deltaspike.core.api.provider.BeanProvider;
import org.apache.deltaspike.core.util.BeanUtils;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.Typed;
import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.AnnotatedParameter;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.InjectionPoint;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Implementation of {@link HandlerMethod}.
 *
 * @param <T> Type of the exception this handler handles.
 */
@Typed()
public class HandlerMethodImpl<T extends Throwable> implements HandlerMethod<T>
{
    private final Class beanClass;
    private Bean<?> bean;
    private final Set<Annotation> qualifiers;
    private final Type exceptionType;
    private final AnnotatedMethod<?> handler;
    private final boolean before;
    private final int ordinal;
    private final Method javaMethod;
    private final AnnotatedParameter<?> handlerParameter;
    private Set<InjectionPoint> injectionPoints;
    private BeanManager beanManager;

    /**
     * Sole Constructor.
     *
     * @param method found handler
     * @param bm     active BeanManager
     * @throws IllegalArgumentException if method is null, has no params or first param is not annotated with
     *                                  {@link Handles} or {@link BeforeHandles}
     */
    public HandlerMethodImpl(final AnnotatedMethod<?> method, final BeanManager bm)
    {
        //validation is done by the extension

        final Set<Annotation> tmpQualifiers = new HashSet<Annotation>();

        handler = method;
        javaMethod = method.getJavaMember();

        handlerParameter = findHandlerParameter(method);

        if (!handlerParameter.isAnnotationPresent(Handles.class)
                && !handlerParameter.isAnnotationPresent(BeforeHandles.class))
        {
            throw new IllegalArgumentException("Method is not annotated with @Handles or @BeforeHandles");
        }

        before = handlerParameter.getAnnotation(BeforeHandles.class) != null;

        if (before)
        {
            ordinal = handlerParameter.getAnnotation(BeforeHandles.class).ordinal();
        }
        else
        {
            ordinal = handlerParameter.getAnnotation(Handles.class).ordinal();
        }

        tmpQualifiers.addAll(BeanUtils.getQualifiers(bm, handlerParameter.getAnnotations()));

        if (tmpQualifiers.isEmpty())
        {
            tmpQualifiers.add(new AnyLiteral());
        }

        qualifiers = tmpQualifiers;
        beanClass = method.getJavaMember().getDeclaringClass();
        exceptionType = ((ParameterizedType) handlerParameter.getBaseType()).getActualTypeArguments()[0];
    }

    /**
     * Determines if the given method is a handler by looking for the {@link Handles} annotation on a parameter.
     *
     * @param method method to search
     * @return true if {@link Handles} is found, false otherwise
     */
    public static boolean isHandler(final AnnotatedMethod<?> method)
    {
        if (method == null)
        {
            throw new IllegalArgumentException("Method must not be null");
        }

        for (AnnotatedParameter<?> param : method.getParameters())
        {
            if (param.isAnnotationPresent(Handles.class) || param.isAnnotationPresent(BeforeHandles.class))
            {
                return true;
            }
        }

        return false;
    }

    public static AnnotatedParameter<?> findHandlerParameter(final AnnotatedMethod<?> method)
    {
        if (!isHandler(method))
        {
            throw new IllegalArgumentException("Method is not a valid handler");
        }

        AnnotatedParameter<?> returnParam = null;

        for (AnnotatedParameter<?> param : method.getParameters())
        {
            if (param.isAnnotationPresent(Handles.class) || param.isAnnotationPresent(BeforeHandles.class))
            {
                returnParam = param;
                break;
            }
        }

        return returnParam;
    }

    public Bean<?> getBean()
    {
        if (bean == null)
        {
            initBean();
        }
        return bean;
    }

    private synchronized void initBean()
    {
        if (bean != null)
        {
            return;
        }

        @SuppressWarnings("unchecked")
        Set<Bean<?>> beans = BeanProvider.getBeanDefinitions(beanClass, false, true);

        if (beans.size() > 1)
        {
            //TODO improve exception
            throw new IllegalStateException(beans.size() + " types found - base type: " + beanClass.getName());
        }
        bean = beans.iterator().next();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Annotation> getQualifiers()
    {
        return Collections.unmodifiableSet(qualifiers);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Type getExceptionType()
    {
        return exceptionType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void notify(final ExceptionEvent<T> event)
    {
        CreationalContext<?> ctx = null;
        try
        {
            ctx = getBeanManager().createCreationalContext(null);
            @SuppressWarnings("unchecked")
            Object handlerInstance = BeanProvider.getContextualReference(beanClass);
            InjectableMethod<?> im = createInjectableMethod(handler, getBean());
            im.invoke(handlerInstance, ctx, new OutboundParameterValueRedefiner(event, this));
        }
        finally
        {
            if (ctx != null)
            {
                ctx.release();
            }
        }
    }

    private <X> InjectableMethod<X> createInjectableMethod(AnnotatedMethod<X> handlerMethod, Bean<?> bean)
    {
        return new InjectableMethod<X>(handlerMethod, bean, getBeanManager());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isBeforeHandler()
    {
        return before;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getOrdinal()
    {
        return ordinal;
    }

    public AnnotatedParameter<?> getHandlerParameter()
    {
        return handlerParameter;
    }

    public Set<InjectionPoint> getInjectionPoints()
    {
        if (injectionPoints == null)
        {
            injectionPoints = new HashSet<InjectionPoint>(handler.getParameters().size() - 1);

            for (AnnotatedParameter<?> param : handler.getParameters())
            {
                if (!param.equals(handlerParameter))
                {
                    injectionPoints.add(
                            new ImmutableInjectionPoint(param, getBeanManager(), getBean(), false, false));
                }
            }

        }
        return new HashSet<InjectionPoint>(injectionPoints);
    }

    private BeanManager getBeanManager()
    {
        if (beanManager == null)
        {
            beanManager = BeanManagerProvider.getInstance().getBeanManager();
        }
        return beanManager;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (o == null || !HandlerMethod.class.isAssignableFrom(o.getClass()))
        {
            return false;
        }

        HandlerMethod<?> that = (HandlerMethod<?>) o;

        if (!qualifiers.equals(that.getQualifiers()))
        {
            return false;
        }
        //noinspection SimplifiableIfStatement
        if (!exceptionType.equals(that.getExceptionType()))
        {
            return false;
        }
        return ordinal == that.getOrdinal();

    }

    @Override
    public int hashCode()
    {
        int result = beanClass.hashCode();
        result = 5 * result + qualifiers.hashCode();
        result = 5 * result + exceptionType.hashCode();
        result = 5 * result + ordinal;
        result = 5 * result + javaMethod.hashCode();
        result = 5 * result + handlerParameter.hashCode();
        return result;
    }

    @Override
    public String toString()
    {
        return new StringBuilder("Qualifiers: ").append(qualifiers).append(" ")
                .append("Handles Type: ").append(exceptionType).append(" ")
                .append("Before: ").append(before).append(" ")
                .append("Precedence: ").append(ordinal).append(" ")
                .append(handler.toString()).toString();
    }
}
