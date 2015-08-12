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

package com.zimbra.cs.pushnotifications;

import java.util.Collection;
import java.util.Map.Entry;
import java.util.Set;

import com.google.common.collect.ImmutableSet;
import com.zimbra.common.localconfig.DebugConfig;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.DataSource;
import com.zimbra.cs.mailbox.Folder;
import com.zimbra.cs.mailbox.MailItem;
import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.mailbox.MailboxListener;
import com.zimbra.cs.mailbox.MailboxManager;
import com.zimbra.cs.mailbox.MailboxOperation;
import com.zimbra.cs.mailbox.Message;
import com.zimbra.cs.pushnotifications.filters.DataSourceInitialSyncFilter;
import com.zimbra.cs.pushnotifications.filters.FilterManager;
import com.zimbra.cs.session.PendingModifications;
import com.zimbra.cs.session.PendingModifications.Change;
import com.zimbra.cs.session.PendingModifications.ModificationKey;

public class PushNotificationListener extends MailboxListener {

    public static final ImmutableSet<MailboxOperation> EVENTS = ImmutableSet.of(
        MailboxOperation.MoveItem, MailboxOperation.RenameItem, MailboxOperation.RenameItemPath,
        MailboxOperation.AlterItemTag);

    public static final ImmutableSet<MailItem.Type> ITEMTYPES = ImmutableSet.of(
        MailItem.Type.FOLDER, MailItem.Type.MESSAGE);

    @Override
    public Set<MailItem.Type> registerForItemTypes() {
        return ITEMTYPES;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.zimbra.cs.mailbox.MailboxListener#notify(com.zimbra.cs.mailbox.
     * MailboxListener.ChangeNotification)
     */
    @Override
    public void notify(ChangeNotification notification) {

        Account account = notification.mailboxAccount;

        if (!FilterManager.executeDefaultFilters(account)) {
            return;
        }

        if (notification.mods.created != null) {
            for (Entry<ModificationKey, MailItem> entry : notification.mods.created.entrySet()) {
                MailItem mailItem = entry.getValue();
                if (DebugConfig.pushNotificationVerboseMode
                    && mailItem.getType() == MailItem.Type.FOLDER) {
                    ZimbraLog.mailbox.info(
                        "ZMG: start building notification for new folder with id=%d",
                        mailItem.getId());
                    pushEvent(notification, account, mailItem);

                } else if (mailItem.getType() == MailItem.Type.MESSAGE) {
                    Message msg = (Message) mailItem;
                    Mailbox mbox;
                    try {
                        mbox = MailboxManager.getInstance().getMailboxByAccount(account);
                        DataSource dataSource = FilterManager.getDataSource(account, msg);
                        String recipient = dataSource != null ? dataSource.getEmailAddress()
                            : account.getName();
                        if (dataSource == null) {
                            if (FilterManager.executeNewMessageFilters(account, msg)) {
                                ZimbraLog.mailbox
                                    .info(
                                        "ZMG: start building notification for new zimbra message with id=%d",
                                        msg.getId());
                                NotificationsManager.getInstance().pushNewMessageNotification(
                                    account, mbox, recipient, msg, notification.op);
                            }
                        } else {
                            DataSourceInitialSyncFilter initialSyncfilter = new DataSourceInitialSyncFilter(
                                account, msg, dataSource);
                            if (FilterManager.executeMessageFileIntoFilter(account, msg,
                                dataSource, initialSyncfilter)) {
                                if (FilterManager
                                    .executeNewMessageFilters(account, msg, dataSource)) {
                                    ZimbraLog.mailbox
                                        .info(
                                            "ZMG: start building notification for new data source message with id=%d",
                                            msg.getId());
                                    NotificationsManager.getInstance().pushNewMessageNotification(
                                        account, mbox, dataSource, recipient, msg, notification.op);
                                } else if (initialSyncfilter.apply()) {
                                    ZimbraLog.mailbox
                                        .info("ZMG: start building notification for data source initial sync");
                                    NotificationsManager.getInstance().pushSyncDataNotification(
                                        account, dataSource, PushNotification.SYNC_DATASOURCE);
                                }
                            }
                        }
                    } catch (Exception e) {
                        ZimbraLog.mailbox.warn(
                            "ZMG: Exception in building notification from mailbox event", e);
                    }
                }
            }
        } else if (DebugConfig.pushNotificationVerboseMode && notification.mods.modified != null
            && EVENTS.contains(notification.op)) {
            Collection<Change> changeList = notification.mods.modified.values();
            if (changeList.size() < PushNotification.MAX_PUSH_NOTIFICATIONS) {
                for (PendingModifications.Change change : changeList) {
                    if (change.what instanceof Folder
                        && notification.op != MailboxOperation.AlterItemTag) {
                        handleEvent(notification, account, change);
                    } else if (change.what instanceof Message) {
                        handleEvent(notification, account, change);
                    }
                }
            } else {
                pushEvent(account);
            }
        } else if (DebugConfig.pushNotificationVerboseMode && notification.mods.deleted != null) {
            Collection<Change> changeList = notification.mods.deleted.values();
            if (changeList.size() < PushNotification.MAX_PUSH_NOTIFICATIONS) {
                for (PendingModifications.Change change : changeList) {
                    MailItem.Type type = (MailItem.Type) change.what;
                    if (type == MailItem.Type.FOLDER || type == MailItem.Type.MESSAGE) {
                        handleEvent(notification, account, change);
                    }
                }
            } else {
                pushEvent(account);
            }
        }
    }

    private void handleEvent(ChangeNotification notification, Account account,
        PendingModifications.Change change) {
        MailItem oldItem = (MailItem) change.preModifyObj;
        if (oldItem == null) {
            return;
        } else {
            pushEvent(notification, account, oldItem);
        }
    }

    private void pushEvent(ChangeNotification notification, Account account, MailItem mailItem) {
        Mailbox mbox;
        try {
            ZimbraLog.mailbox.info("ZMG: start building notification for modified item with id=%d",
                mailItem.getId());
            mbox = MailboxManager.getInstance().getMailboxByAccount(account);
            NotificationsManager.getInstance().pushSyncDataNotification(mbox, mailItem, notification.op);
        } catch (Exception e) {
            ZimbraLog.mailbox.warn("ZMG: Exception in building notification from mailbox event", e);
        }
    }

    private void pushEvent(Account account) {
        try {
            ZimbraLog.mailbox.info("ZMG: start building content available push notification");
            NotificationsManager.getInstance().pushContentAvailableNotification(account);
        } catch (Exception e) {
            ZimbraLog.mailbox.warn("ZMG: Exception in building notification from mailbox event", e);
        }
    }

}
