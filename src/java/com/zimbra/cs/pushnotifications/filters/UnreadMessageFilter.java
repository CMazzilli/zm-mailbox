/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2015 Zimbra, Inc.
 * 
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.3 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */

package com.zimbra.cs.pushnotifications.filters;

import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.mailbox.Message;

public class UnreadMessageFilter implements Filter {

    private Message message;

    public UnreadMessageFilter(Message message) {
        this.message = message;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.zimbra.cs.pushnotifications.filters.Filter#apply()
     */
    @Override
    public boolean apply() {
        if (message == null) {
            return false;
        }

        if (!message.isUnread()) {
            ZimbraLog.mailbox.debug("ZMG: Message is read");
            return false;
        }
        return true;
    }

}
