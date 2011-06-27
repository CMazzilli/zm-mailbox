package com.zimbra.cs.mailbox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Strings;
import com.google.common.collect.TreeMultimap;

import com.zimbra.common.account.Key.AccountBy;
import com.zimbra.common.mailbox.ContactConstants;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.Element;
import com.zimbra.common.soap.MailConstants;
import com.zimbra.common.soap.SoapHttpTransport;
import com.zimbra.common.soap.SoapProtocol;
import com.zimbra.common.util.StringUtil;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.AuthToken;
import com.zimbra.cs.account.GalContact;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.gal.GalSearchControl;
import com.zimbra.cs.gal.GalSearchParams;
import com.zimbra.cs.gal.GalSearchResultCallback;
import com.zimbra.cs.httpclient.URLUtil;
import com.zimbra.cs.mime.ParsedContact;
import com.zimbra.cs.service.util.ItemId;

public class ContactGroup {
    
    // metadata keys for ContactGroup 
    private enum MetadataKey {
        LASTID("lid"),
        MEMBERS("m");
        
        private String key;
        MetadataKey(String key) {
            this.key = key;
        }
        
        private String getKey() {
            return key;
        }
    }
    
    public static class MemberId {
        private int id;
        
        private MemberId(int id) {
            this.id = id;
        }
        
        public static MemberId fromString(String idStr) throws ServiceException {
            try {
                Integer id = Integer.valueOf(idStr);
                return new MemberId(id.intValue());
            } catch (NumberFormatException e) {
                throw ServiceException.FAILURE("invalid member id: " + idStr, e);
            }
        }
        
        @Override
        public boolean equals(Object obj) {
            return ((obj instanceof MemberId) && (id == ((MemberId) obj).id));
        }
        
        @Override
        public int hashCode() { 
            return id; 
        }
        
        @Override
        public String toString() {
            return "" + id;
        }
        
        private String getMetaDataEncoded() {
            return toString();
        }
    }
    
    private int lastMemberId;
    
    // ordered map, order is the order in which keys were inserted into the map (insertion-order). 
    // Note that insertion order is not affected if a key is re-inserted into the map. 
    // We need to maintain the order members are added to the group (do we?), and
    // need to be able to quickly get a member by a unique key
    //
    // In members are persisted in MetadataList, which is ordered.
    private Map<MemberId, Member> members = new LinkedHashMap<MemberId, Member>();  // ordered map
    
    // never persisted
    // contains derefed members sorted by the Member.getKey() order
    private TreeMultimap<String, Member> derefedMembers = null;
    
    public static ContactGroup init(Contact contact, boolean createIfNotExist) throws ServiceException {
        ContactGroup contactGroup = null;
        if (contact != null) {
            String encoded = contact.get(ContactConstants.A_groupMember);
            if (encoded != null) {
                contactGroup = init(encoded);
            }
        }
        
        if (contactGroup == null && createIfNotExist) {
            contactGroup = init();
        }
        return contactGroup;
    }
    
    public static ContactGroup init(String encoded) throws ServiceException {
        return ContactGroup.decode(encoded);
    }
    
    public static ContactGroup init() throws ServiceException {
        return new ContactGroup();
    }
    
    private ContactGroup() {
        this(0);
    }
    
    private ContactGroup(int lastMemberId) {
        this.lastMemberId = lastMemberId;
    }
    
    private MemberId getNextMemberId() {
        return new MemberId(++lastMemberId);
    }
    
    private boolean isDerefed() {
        return derefedMembers != null;
    }

    private void addMember(Member member) {
        members.put(member.getId(), member);
    }
    
    private void replaceMember(Member member) {
        members.put(member.getId(), member);
    }
    
    private Member createMember(MemberId reuseId, Member.Type type, String value) throws ServiceException {
        Member member = null;
        MemberId memberId = (reuseId == null) ? getNextMemberId() : reuseId;
        
        switch (type) {
            case CONTACT_REF:
                member = new ContactRefMember(memberId, value);
                break;
            case GAL_REF:  
                member = new GalRefMember(memberId, value);
                break;
            case INLINE: 
                member = new ContactGroup.InlineMember(memberId, value);
                break;
            default:
                throw ServiceException.INVALID_REQUEST("Unrecognized member type: " + type.name(), null);
        }
        return member;
    }
    
    public Member getMemberById(MemberId id) {
        return members.get(id);
    }
    
    public void deleteAllMembers() {
        members.clear();
    }
    
    /*
     * return members in the order they were inserted
     */
    
    public List<Member> getMembers() {
        return Arrays.asList(members.values().toArray(new Member[members.size()]));
    }
    
    public List<Member> getMembers(boolean preferDerefed) {
        if (preferDerefed && isDerefed()) {
            return getDerefedMembers();
        } else {
            return getMembers();
        }
    }
    
    /*
     * return derefed members in Member.getKey() order
     */
    public List<Member> getDerefedMembers() {
        assert(isDerefed());
        return Arrays.asList(derefedMembers.values().toArray(new Member[derefedMembers.size()]));
    }
    
    // create and add member at the end of the list
    public Member addMember(Member.Type type, String value) throws ServiceException {
        Member member = createMember(null, type, value);
        addMember(member);
        return member;
    }
    
    public void removeMember(MemberId memberId) {
        members.remove(memberId);
    }
    
    public void modifyMember(MemberId memberId, Member.Type type, String value) throws ServiceException {
        Member member = members.get(memberId);
        if (member == null) {
            throw ServiceException.INVALID_REQUEST("no such member: " + memberId, null);
        }
        
        if (type == null || member.getType() == type) {
            member.setValue(value);
        } else {
            Member updatedMember = createMember(memberId, type, value);
            replaceMember(updatedMember);
        }
    }
    
    /*
     * Note: deref each time when called, result is not cached
     * 
     * Return all members expanded in key order.  
     * Key is:
     *  for CONTACT_REF: the fileAs field of the Contact
     *  for GAL_REF: email address of the GAL entry
     *  for CONTACT_REF: the value
     */
    public void derefAllMembers(Mailbox mbox, OperationContext octxt) {
        derefedMembers = TreeMultimap.create();
        
        for (Member member : members.values()) {
            member.derefMember(mbox, octxt);
            if (member.derefed()) {
                String key = member.getDerefedKey();
                derefedMembers.put(key, member);
            } else {
                ZimbraLog.contact.debug("contact group member cannot be derefed: " + member.getValue());
                derefedMembers.put(member.getValue(), member);
            }
        }
    }
    
    
    public List<String> getAllEmailAddresses(boolean refresh, Mailbox mbox, OperationContext octxt) {
        if (refresh || !isDerefed()) {
            derefAllMembers(mbox, octxt);
        }
        
        List<String> result = new ArrayList<String>();
        for (Member member : members.values()) {
            member.getEmailAddresses(result);
        }
        return result;
    }
    
    public String encode() {
        Metadata encoded = new Metadata();
        
        MetadataList encodedMembers = new MetadataList();
        for (Member member : members.values()) {
            encodedMembers.add(member.encode());
        }
        
        encoded.put(MetadataKey.LASTID.getKey(), lastMemberId);
        encoded.put(MetadataKey.MEMBERS.getKey(), encodedMembers);
        
        return encoded.toString();
    }
    
    private static ContactGroup decode(String encodedStr) throws ServiceException {
        try {
            Metadata encoded = new Metadata(encodedStr);
            int lastMemberId = encoded.getInt(MetadataKey.LASTID.getKey(), -1);
            if (lastMemberId == -1) {
                throw ServiceException.FAILURE("missing last member id in metadata", null);
            }
            
            ContactGroup contactGroup = new ContactGroup(lastMemberId);
            
            MetadataList members = encoded.getList(MetadataKey.MEMBERS.getKey());
            if (members == null) {
                throw ServiceException.FAILURE("missing members in metadata", null);
            }
            
            List<Metadata> memberList = members.asList();
            for (Metadata encodedMember : memberList) {
                Member member = Member.decode(encodedMember);
                contactGroup.addMember(member);
            }
            return contactGroup;
            
        } catch (ServiceException e) {
            ZimbraLog.contact.warn("unabale to decode contact group", e);
            throw e;
        }
        
    }
    
    
    /*
     *======================
     * Group Member classes
     *======================
     */
    public static abstract class Member implements Comparable<Member> {
        
        // metadata keys for member data
        private enum MetadataKey {
            ID("id"),
            TYPE("t"),
            VALUE("v");
            
            private String key;
            MetadataKey(String key) {
                this.key = key;
            }
            
            private String getKey() {
                return key;
            }
        }
        
        // type encoded/stored in metadata - do not change the encoded value
        public static enum Type {
            CONTACT_REF("C", ContactConstants.GROUP_MEMBER_TYPE_CONTACT_REF),  // ContactRefMember
            GAL_REF("G", ContactConstants.GROUP_MEMBER_TYPE_GAL_REF),          // ContactRefMember
            INLINE("I", ContactConstants.GROUP_MEMBER_TYPE_INLINE);            // InlineMember
            
            private String metadataEncoded;
            private String soapEncoded;
            
            Type(String metadataEncoded, String soapEncoded) {
                this.metadataEncoded = metadataEncoded;
                this.soapEncoded = soapEncoded;
            }
            
            private String getMetaDataEncoded() {
                return metadataEncoded;
            }
            
            public String getSoapEncoded() {
                return soapEncoded;
            }
            
            private static Type fromMetadata(String metadataEncoded) throws ServiceException {
                if (CONTACT_REF.getMetaDataEncoded().equals(metadataEncoded)) {
                    return CONTACT_REF;
                } else if (GAL_REF.getMetaDataEncoded().equals(metadataEncoded)) {
                    return GAL_REF;
                } else if (INLINE.getMetaDataEncoded().equals(metadataEncoded)) {
                    return INLINE;
                }
                
                throw ServiceException.FAILURE("Unrecognized member type: " + metadataEncoded, null);
            }
            
            public static Type fromSoap(String soapEncoded) throws ServiceException {
                if (soapEncoded == null) {
                    return null;
                } else if (CONTACT_REF.getSoapEncoded().equals(soapEncoded)) {
                    return CONTACT_REF;
                } else if (GAL_REF.getSoapEncoded().equals(soapEncoded)) {
                    return GAL_REF;
                } else if (INLINE.getSoapEncoded().equals(soapEncoded)) {
                    return INLINE;
                }
                
                throw ServiceException.INVALID_REQUEST("Unrecognized member type: " + soapEncoded, null);
            }
        }
        
        private MemberId id;  // unique id of the member within this group
        protected String value;
        private String derefedKey; // key for sorting in the expanded group
        private List<String> derefedEmailAddrs; // derefed email addresses of the member
        private Object derefedObject;
        
        public abstract Type getType();

        
        // load the actual entry
        protected abstract void deref(Mailbox mbox, OperationContext octxt) throws ServiceException;  
        
        protected Member(MemberId id, String value) throws ServiceException {
            this.id = id;
            setValue(value);
        }
        
        /*
         * called from TreeMultimap (ContactGroup.derefAllMembers) when two 
         * derefed keys are the same.  return id order in this case.
         * 
         * Note: must not return 0 here, if we do, the member will not be 
         *       inserted into the TreeMultimap.
         */
        @Override
        public int compareTo(Member other) {
            return getId().toString().compareTo(other.getId().toString());
        }
        
        public MemberId getId() {
            return id;
        }
        
        public String getValue() {
            return value;
        }
        
        public String getDerefedKey() {
            assert(derefed());
            return derefedKey;
        }
        
        // if result is not null, append email addresses of the member into result
        // if result is null, create a new List filled with email addresses of the member
        // return the List into which email addresses are added
        private List<String> getEmailAddresses(List<String> result) {
            assert(derefed());
            if (result == null) {
                result = new ArrayList<String>();
            }
            
            if (derefedEmailAddrs != null) {
                result.addAll(derefedEmailAddrs);
            }
            return result;
        }
        
        public Object getDerefedObj() {
            return derefedObject;
        }
        
        protected boolean derefed() {
            return getDerefedObj() != null;
        }
        
        private void derefMember(Mailbox mbox, OperationContext octxt) {
            if (!derefed()) {
                try {
                    deref(mbox, octxt);
                } catch (ServiceException e) {
                    // log and continue
                    ZimbraLog.contact.warn("unable to deref contact group member: " + value, e);
                }
            }
        }
        
        private void setValue(String value) throws ServiceException {
            if (StringUtil.isNullOrEmpty(value)) {
                throw ServiceException.INVALID_REQUEST("missing value", null);
            }
            this.value = value;
        }
        
        private void setDerefedKey(String key) {
            if (key == null) {
                key = "";
            }
            this.derefedKey = key;
        }
        
        private void setDerefedEmailAddrs(List<String> emaiLAddrs) {
            this.derefedEmailAddrs = emaiLAddrs;
        }
        
        protected void setDerefedObject(Object obj, String key, List<String> emailAddrs) {
            if (obj != null) {
                setDerefedKey(key);
                setDerefedEmailAddrs(emailAddrs);
            }
            this.derefedObject = obj;
        }
        
        private Metadata encode() {
            Metadata encoded = new Metadata();
            encoded.put(MetadataKey.ID.getKey(), id.getMetaDataEncoded());
            encoded.put(MetadataKey.TYPE.getKey(), getType().getMetaDataEncoded());
            encoded.put(MetadataKey.VALUE.getKey(), value);
            return encoded;
        }
        
        private static Member decode(Metadata encoded) throws ServiceException {
            String idStr = encoded.get(MetadataKey.ID.getKey());
            String encodedType = encoded.get(MetadataKey.TYPE.getKey());
            String value = encoded.get(MetadataKey.VALUE.getKey());
            
            MemberId id = MemberId.fromString(idStr);
            Type type = Type.fromMetadata(encodedType);
            switch (type) {
                case CONTACT_REF:
                    return new ContactRefMember(id, value);
                case GAL_REF:  
                    return new GalRefMember(id, value);
                case INLINE: 
                    return new InlineMember(id, value);
            }
            throw ServiceException.FAILURE("Unrecognized member type: " + encodedType, null);
        }
    }
    
    public static class ContactRefMember extends Member {
        public ContactRefMember(MemberId id, String value) throws ServiceException {
            super(id, value);
        }

        @Override
        public Type getType() {
            return Type.CONTACT_REF;
        }
        
        private String genDerefedKey(Contact contact) throws ServiceException {
            return contact.getFileAsString();
        }
        
        private String genDerefedKey(Element eContact) throws ServiceException {
            return eContact.getAttribute(MailConstants.A_FILE_AS_STR, null);
        }
        
        private List<String> genDerefedEmailAddrs(Account ownerAcct, Contact contact) {
            String emailFields[] = Contact.getEmailFields(ownerAcct);
            Map<String, String> fieldMap = contact.getAllFields(); 
            
            List<String> result = new ArrayList<String>();
            for (String field : emailFields) {
                String addr = fieldMap.get(field);
                if (addr != null && !addr.trim().isEmpty()) {
                    result.add(addr);
                }
            }
            return result;
        }
        
        private List<String> genDerefedEmailAddrs(Account ownerAcct, Element eContact) {
            String emailFields[] = Contact.getEmailFields(ownerAcct);
            Set<String> emailFieldsSet = new HashSet<String>(Arrays.asList(emailFields));

            List<String> result = new ArrayList<String>();
            for (Element eAttr : eContact.listElements(MailConstants.E_ATTRIBUTE)) {
                String field = eAttr.getAttribute(MailConstants.A_ATTRIBUTE_NAME, null);
                if (field != null && emailFieldsSet.contains(field)) {
                    String content = eAttr.getText();
                    if (!Strings.isNullOrEmpty(content)) {
                        result.add(content);
                    }
                }
            }
            
            return result;
        }

        @Override
        protected void deref(Mailbox requestedMbox, OperationContext octxt) 
        throws ServiceException {
            Object obj = null;
            String key = null;
            List<String> emailAddrs = null;
            
            ItemId itemId = new ItemId(value, requestedMbox.getAccountId());
            String ownerAcctId = itemId.getAccountId();
            Account ownerAcct = Provisioning.getInstance().get(AccountBy.id, ownerAcctId);
            if (ownerAcct == null) {
                ZimbraLog.contact.debug("no such account for contact group member: " + itemId.toString());
                return;
            }
            
            if (itemId.isLocal()) {
                Mailbox mbox = MailboxManager.getInstance().getMailboxByAccountId(itemId.getAccountId(), false);
                Contact contact = mbox.getContactById(octxt, itemId.getId());
                if (contact != null) {
                    obj = contact;
                    key = genDerefedKey(contact);
                    emailAddrs = genDerefedEmailAddrs(ownerAcct, contact);
                }
            } else {
                Element eContact = fetchRemoteContact(octxt.getAuthToken(), ownerAcct, itemId);
                if (eContact != null) {
                    obj = eContact;
                    key = genDerefedKey(eContact);
                    emailAddrs = genDerefedEmailAddrs(ownerAcct, eContact);
                }
            }
            
            setDerefedObject(obj, key, emailAddrs);
        }
        
        private Element fetchRemoteContact(AuthToken authToken, Account ownerAcct, ItemId contactId)
        throws ServiceException {
            Provisioning prov = Provisioning.getInstance();
            
            String serverUrl = URLUtil.getAdminURL(prov.getServerByName(ownerAcct.getMailHost()));
            SoapHttpTransport transport = new SoapHttpTransport(serverUrl);
            transport.setAuthToken(authToken.toZAuthToken());
            transport.setTargetAcctId(ownerAcct.getId());
            
            Element request = Element.create(SoapProtocol.Soap12, MailConstants.GET_CONTACTS_REQUEST);
            Element eContact = request.addElement(MailConstants.E_CONTACT);
            eContact.addAttribute(MailConstants.A_ID, contactId.toString());
            
            Element response;
            try {
                response = transport.invokeWithoutSession(request);
            } catch (IOException e) {
                ZimbraLog.contact.debug("unable to fetch remote member ", e);
                throw ServiceException.PROXY_ERROR("unable to fetch remote member " + contactId.toString(), serverUrl);
            }
            Element eGotContact = response.getOptionalElement(MailConstants.E_CONTACT);
            if (eGotContact != null) {
                eGotContact.detach();
            }
            return eGotContact;
        }
    }
    
    public static class GalRefMember extends Member {
        private static final String PRIMARY_EMAIL_FIELD = "email";
        private static final Set<String> GAL_EMAIL_FIELDS = new HashSet<String>(Arrays.asList(
                new String[] {
                        PRIMARY_EMAIL_FIELD, "email2", "email3", "email4", "email5", "email6", 
                        "email7", "email8", "email9", "email10", "email11", "email12", "email13", 
                        "email14", "email15", "email16"
                }));
        
        public GalRefMember(MemberId id, String value) throws ServiceException {
            super(id, value);
        }

        @Override
        public Type getType() {
            return Type.GAL_REF;
        }
        
        private String genDerefedKey(Contact contact) throws ServiceException {
            return contact.getFileAsString();
        }
        
        private String genDerefedKey(GalContact galContact) throws ServiceException {
            String key = galContact.getSingleAttr(ContactConstants.A_email);
            if (key == null) {
                key = galContact.getSingleAttr(ContactConstants.A_fullName);
            }
            if (key == null) {
                key = galContact.getSingleAttr(ContactConstants.A_firstName) + " " +
                    galContact.getSingleAttr(ContactConstants.A_lastName);
            }
            return key;
        }
        
        private String genDerefedKey(Element eContact) throws ServiceException {
            // a proxied GAL sync account entry, must have a fileAs string
            return eContact.getAttribute(MailConstants.A_FILE_AS_STR, null);
        }
        
        private List<String> genDerefedEmailAddrs(Contact contact) {
            Map<String, String> fieldMap = contact.getAllFields(); 
            
            List<String> result = new ArrayList<String>();
            for (String field : GAL_EMAIL_FIELDS) {
                String addr = fieldMap.get(field);
                if (addr != null && !addr.trim().isEmpty()) {
                    result.add(addr);
                }
            }
            return result;
        }
        
        private List<String> genDerefedEmailAddrs(GalContact galContact) {
            Map<String, Object> fieldMap = galContact.getAttrs();
            
            List<String> result = new ArrayList<String>();
            for (String field : GAL_EMAIL_FIELDS) {
                Object value = fieldMap.get(field);
                if (value instanceof String) {
                    result.add((String) value);
                } else if (value instanceof String[]) {
                    String[] addrs = (String[]) value;
                    for (String addr : addrs) {
                        result.add(addr);
                    }
                }
            }
            return result;
        }
        
        private List<String> genDerefedEmailAddrs(Element eContact) {

            List<String> result = new ArrayList<String>();
            for (Element eAttr : eContact.listElements(MailConstants.E_ATTRIBUTE)) {
                String field = eAttr.getAttribute(MailConstants.A_ATTRIBUTE_NAME, null);
                if (field != null && GAL_EMAIL_FIELDS.contains(field)) {
                    String content = eAttr.getText();
                    if (!Strings.isNullOrEmpty(content)) {
                        result.add(content);
                    }
                }
            }
            
            return result;
        }

        @Override
        protected void deref(Mailbox mbox, OperationContext octxt) 
        throws ServiceException {
            // search GAL by DN
            GalSearchParams params = new GalSearchParams(mbox.getAccount(), null);
            params.setSearchEntryByDn(value);
            params.setType(Provisioning.GalSearchType.all);
            params.setLimit(1);
            
            // params.setExtraQueryCallback(new ContactGroupExtraQueryCallback(value));
            ContactGroupResultCallback callback = new ContactGroupResultCallback(params);
            params.setResultCallback(callback);
            
            GalSearchControl gal = new GalSearchControl(params);
            gal.search(); 
            
            Object obj = callback.getResult(); 
            String key = null;
            List<String> emailAddrs = null;
            
            if (obj != null) {
                if (obj instanceof Contact) {
                    Contact contact = (Contact) obj;
                    key = genDerefedKey(contact);
                    emailAddrs = genDerefedEmailAddrs(contact);
                } else if (obj instanceof GalContact) {
                    GalContact galContact = (GalContact) obj;
                    key = genDerefedKey(galContact);
                    emailAddrs = genDerefedEmailAddrs(galContact);
                } else if (obj instanceof Element) {
                    Element eContact = (Element) obj;
                    key = genDerefedKey(eContact);
                    emailAddrs = genDerefedEmailAddrs(eContact);
                }
            }
            
            setDerefedObject(obj, key, emailAddrs);
        }

        private static class ContactGroupResultCallback extends GalSearchResultCallback {
            Object result;
            
            public ContactGroupResultCallback(GalSearchParams params) {
                super(params);
            }
            
            private Object getResult() {
                return result;
            }
            
            @Override
            public Element handleContact(Contact contact) throws ServiceException {
                result = contact;
                return null; 
            }
            
            @Override
            public void handleContact(GalContact galContact) throws ServiceException {
                result = galContact;
            }
            
            @Override
            public void handleElement(Element element) throws ServiceException {
                element.detach();
                result = element; // will be attached directly to the outut element in ToXML.
            }
        }

    }
    
    public static class InlineMember extends Member {
        public InlineMember(MemberId id, String value) throws ServiceException {
            super(id, value);
        }

        @Override
        public Type getType() {
            return Type.INLINE;
        }

        @Override
        protected void deref(Mailbox mbox, OperationContext octxt) {
            // value is the derefed obj, the key, and the email
            List<String> emailAddrs = new ArrayList<String>();
            emailAddrs.add(value);
            
            setDerefedObject(value, value, emailAddrs);
        }
    }
    
    
    /*
     * =============================
     * Migrate dlist to groupMember
     * =============================
     */
    public static class MigrateContactGroup {
        /*
         * dlist is a String of comma-seperated email address with optional display part.
         * There could be comma in the display part.
         * e.g
         * "Ballard, Martha" <martha34@aol.com>, "Davidson, Ross" <rossd@example.zimbra.com>, user1@test.com
         * 
         * This should be split to:
         * "Ballard, Martha" <martha34@aol.com>
         * "Davidson, Ross" <rossd@example.zimbra.com>
         * user1@test.com
         */
        private static final Pattern PATTERN = Pattern.compile("(([\\s]*)(\"[^\"]*\")*[^,]*[,]*)");
        
        private Mailbox mbox;
        private OperationContext octxt;
        
        public MigrateContactGroup(Mailbox mbox) throws ServiceException {
            this.mbox = mbox;
            octxt = new OperationContext(mbox);
        }
        
        public void handle() throws ServiceException {
            for (MailItem item : mbox.getItemList(octxt, MailItem.Type.CONTACT, -1)) {
                Contact contact = (Contact) item;
                migrate(contact);
            }
        }
        
        private void migrate(Contact contact) throws ServiceException {
            if (!contact.isGroup()) {
                return;
            }
            
            String dlist = contact.get(ContactConstants.A_dlist);
            if (Strings.isNullOrEmpty(dlist)) {
                return;
            }
            
            ContactGroup contactGroup = ContactGroup.init();
            
            // add each dlist member as an inlined member in groupMember
            Matcher matcher = PATTERN.matcher(dlist);
            while (matcher.find()) {
                String token = matcher.group();
                int len = token.length();
                if (len > 0) {
                    if (token.charAt(len-1) == ',') {
                        token = token.substring(0, len-1);
                    }
                    String addr = token.trim();
                    if (!addr.isEmpty()) {
                        contactGroup.addMember(Member.Type.INLINE, addr);
                    }
                }
            }
            
            ParsedContact pc = new ParsedContact(contact);
            // do NOT delete dlist for backward compatibility, old ZCO/desktop/mobile
            // clients are still out there.
            pc.modifyField(ContactConstants.A_groupMember, contactGroup.encode());
            mbox.modifyContact(octxt, contact.getId(), pc);
        }
    }
    
}
