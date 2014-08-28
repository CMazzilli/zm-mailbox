/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2014 Zimbra Software, LLC.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software Foundation,
 * version 2 of the License.
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.mailbox;

import com.zimbra.common.service.ServiceException;
import com.zimbra.cs.util.Zimbra;

/**
 * Unit test for {@link LocalSentMessageIdCache}.
 */
public final class LocalSentMessageIdCacheTest extends AbstractSentMessageIdCacheTest {

    @Override
    protected SentMessageIdCache constructCache() throws ServiceException {
        SentMessageIdCache cache = new LocalSentMessageIdCache();
        Zimbra.getAppContext().getAutowireCapableBeanFactory().autowireBean(cache);
        return cache;
    }

    @Override
    protected boolean isExternalCacheAvailableForTest() throws Exception {
        return true;
    }

    @Override
    protected void flushCacheBetweenTests() throws Exception {
        ((LocalSentMessageIdCache)cache).flush();
    }
}
