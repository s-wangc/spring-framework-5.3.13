/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.web.servlet.view;

import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

/**
 * Convenient subclass of {@link UrlBasedViewResolver} that supports
 * {@link InternalResourceView} (i.e. Servlets and JSPs) and subclasses
 * such as {@link JstlView}.
 *
 * <p>The view class for all views generated by this resolver can be specified
 * via {@link #setViewClass}. See {@link UrlBasedViewResolver}'s javadoc for details.
 * The default is {@link InternalResourceView}, or {@link JstlView} if the
 * JSTL API is present.
 *
 * <p>BTW, it's good practice to put JSP files that just serve as views under
 * WEB-INF, to hide them from direct access (e.g. via a manually entered URL).
 * Only controllers will be able to access them then.
 *
 * <p><b>Note:</b> When chaining ViewResolvers, an InternalResourceViewResolver
 * always needs to be last, as it will attempt to resolve any view name,
 * no matter whether the underlying resource actually exists.
 *
 * @author Juergen Hoeller
 * @since 17.02.2003
 * @see #setViewClass
 * @see #setPrefix
 * @see #setSuffix
 * @see #setRequestContextAttribute
 * @see InternalResourceView
 * @see JstlView
 */
public class InternalResourceViewResolver extends UrlBasedViewResolver {

	private static final boolean jstlPresent = ClassUtils.isPresent(
			"javax.servlet.jsp.jstl.core.Config", InternalResourceViewResolver.class.getClassLoader());

	@Nullable
	private Boolean alwaysInclude;


	/**
	 * Sets the default {@link #setViewClass view class} to {@link #requiredViewClass}:
	 * by default {@link InternalResourceView}, or {@link JstlView} if the JSTL API
	 * is present.
	 */
	public InternalResourceViewResolver() {
		Class<?> viewClass = requiredViewClass();
		if (InternalResourceView.class == viewClass && jstlPresent) {
			viewClass = JstlView.class;
		}
		setViewClass(viewClass);
	}

	/**
	 * A convenience constructor that allows for specifying {@link #setPrefix prefix}
	 * and {@link #setSuffix suffix} as constructor arguments.
	 * @param prefix the prefix that gets prepended to view names when building a URL
	 * @param suffix the suffix that gets appended to view names when building a URL
	 * @since 4.3
	 */
	public InternalResourceViewResolver(String prefix, String suffix) {
		this();
		setPrefix(prefix);
		setSuffix(suffix);
	}


	/**
	 * Specify whether to always include the view rather than forward to it.
	 * <p>默认值是"false". Switch this flag on to enforce the use of a
	 * Servlet include, even if a forward would be possible.
	 * @see InternalResourceView#setAlwaysInclude
	 */
	public void setAlwaysInclude(boolean alwaysInclude) {
		this.alwaysInclude = alwaysInclude;
	}


	@Override
	protected Class<?> requiredViewClass() {
		return InternalResourceView.class;
	}

	@Override
	protected AbstractUrlBasedView instantiateView() {
		return (getViewClass() == InternalResourceView.class ? new InternalResourceView() :
				(getViewClass() == JstlView.class ? new JstlView() : super.instantiateView()));
	}

	@Override
	protected AbstractUrlBasedView buildView(String viewName) throws Exception {
		InternalResourceView view = (InternalResourceView) super.buildView(viewName);
		if (this.alwaysInclude != null) {
			view.setAlwaysInclude(this.alwaysInclude);
		}
		view.setPreventDispatchLoop(true);
		return view;
	}

}
