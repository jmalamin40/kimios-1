/*
 * Kimios - Document Management System Software
 * Copyright (C) 2008-2015  DevLib'
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License
 * aong with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kimios.kernel.share.controller.impl;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.MultiPartEmail;
import org.kimios.api.events.annotations.DmsEvent;
import org.kimios.api.events.annotations.DmsEventName;
import org.kimios.api.templates.ITemplate;
import org.kimios.api.templates.ITemplateProcessor;
import org.kimios.api.templates.ITemplateProvider;
import org.kimios.api.templates.TemplateType;
import org.kimios.exceptions.AccessDeniedException;
import org.kimios.exceptions.ConfigException;
import org.kimios.exceptions.DmsKernelException;
import org.kimios.kernel.configuration.Config;
import org.kimios.kernel.controller.AKimiosController;
import org.kimios.kernel.controller.IFileTransferController;
import org.kimios.kernel.dms.model.DMEntity;
import org.kimios.kernel.dms.model.DMEntityImpl;
import org.kimios.kernel.dms.model.Document;
import org.kimios.kernel.dms.model.DocumentVersion;
import org.kimios.kernel.filetransfer.model.DataTransfer;
import org.kimios.kernel.security.model.Session;
import org.kimios.kernel.share.controller.IMailShareController;
import org.kimios.kernel.share.factory.MailContactFactory;
import org.kimios.kernel.share.factory.ShareFactory;
import org.kimios.kernel.share.mail.EmailFactory;
import org.kimios.kernel.share.mail.MailDescriptor;
import org.kimios.kernel.share.mail.MailTaskRunnable;
import org.kimios.kernel.share.model.MailContact;
import org.kimios.kernel.share.model.Share;
import org.kimios.kernel.share.model.ShareStatus;
import org.kimios.kernel.share.model.ShareType;
import org.kimios.kernel.user.FactoryInstantiator;
import org.kimios.kernel.user.model.User;
import org.kimios.utils.configuration.ConfigurationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;


@Transactional
public class MailShareController extends AKimiosController implements IMailShareController {

    private static Logger logger = LoggerFactory.getLogger(MailShareController.class);

    private String mailerSender = "Kimios'";

    private String mailerSenderMail = "kimios@kimios.org";

    public String getMailerSenderMail() {
        return mailerSenderMail;
    }

    public void setMailerSenderMail(String mailerSenderMail) {
        this.mailerSenderMail = mailerSenderMail;
    }

    public String getMailerSender() {
        return mailerSender;
    }

    public void setMailerSender(String mailerSender) {
        this.mailerSender = mailerSender;
    }

    private EmailFactory emailFactory;

    public EmailFactory getEmailFactory() {
        return emailFactory;
    }

    public void setEmailFactory(EmailFactory emailFactory) {
        this.emailFactory = emailFactory;
    }

    private ScheduledExecutorService scheduledExecutorService;

    private MailContactFactory mailContactFactory;

    public MailContactFactory getMailContactFactory() {
        return mailContactFactory;
    }

    public void setMailContactFactory(MailContactFactory mailContactFactory) {
        this.mailContactFactory = mailContactFactory;
    }

    public MailShareController(ShareFactory sFactory){
        this.shareFactory = sFactory;
        scheduledExecutorService = createScheduledExecutorService();
    }

    public MailShareController(EmailFactory emailFactory, MailContactFactory mailContactFactory,
                               ITemplateProvider templateProvider, ITemplateProcessor templateProcessor,
                               IFileTransferController fileTransferController, ShareFactory shareFactory) {
        this.scheduledExecutorService = createScheduledExecutorService();
        this.shareFactory = shareFactory;
        this.emailFactory = emailFactory;
        this.mailContactFactory = mailContactFactory;
        this.templateProvider = templateProvider;
        this.templateProcessor = templateProcessor;
        this.fileTransferController = fileTransferController;
    }

    private ScheduledExecutorService createScheduledExecutorService() {
        return Executors.newScheduledThreadPool(8);
    }

    private ITemplateProvider templateProvider;

    private ITemplateProcessor templateProcessor;

    private IFileTransferController fileTransferController;

    private ShareFactory shareFactory;

    public ITemplateProvider getTemplateProvider() {
        return templateProvider;
    }

    public void setTemplateProvider(ITemplateProvider templateProvider) {
        this.templateProvider = templateProvider;
    }

    public ITemplateProcessor getTemplateProcessor() {
        return templateProcessor;
    }

    public void setTemplateProcessor(ITemplateProcessor templateProcessor) {
        this.templateProcessor = templateProcessor;
    }

    public IFileTransferController getFileTransferController() {
        return fileTransferController;
    }

    public void setFileTransferController(IFileTransferController fileTransferController) {
        this.fileTransferController = fileTransferController;
    }

    public ShareFactory getShareFactory() {
        return shareFactory;
    }

    public void setShareFactory(ShareFactory shareFactory) {
        this.shareFactory = shareFactory;
    }

    private MultiPartEmail initShareNotificationEmail (Session session, Map<String, String> recipients,
                                                       String subject,
                                                       String senderAddress, String senderName,
                                                       boolean defaultSender) throws EmailException {

        MultiPartEmail email = emailFactory.getMultipartEmailObject();

        for (String emailAddress : recipients.keySet()) {
            email.addTo(emailAddress, recipients.get(emailAddress));
            mailContactFactory.addContact(emailAddress.toLowerCase(), recipients.get(emailAddress));
        }

        String copyToMailAddress = ConfigurationManager.getValue("dms.share.mail.copy.to");
        if (StringUtils.isNotBlank(copyToMailAddress)) {
            email.addCc(copyToMailAddress);
        }


        if (StringUtils.isNotBlank(senderAddress)) {
            if (StringUtils.isNotBlank(senderName)) {
                email.setFrom(senderAddress, senderName);
            } else {
                email.setFrom(senderAddress);
            }
        } else if (defaultSender && StringUtils.isNotBlank(mailerSenderMail)) {
            email.setFrom(mailerSenderMail, mailerSender);
        } else {
            //send with user's mail address
            User u = authFactoryInstantiator.getAuthenticationSourceFactory().getAuthenticationSource(session.getUserSource())
                    .getUserFactory().getUser(session.getUserName());
            if (StringUtils.isNotBlank(u.getMail())) {
                email.setFrom(u.getMail());
                email.setBounceAddress(u.getMail());
            }
        }
        email.setSubject(subject);

        return email;
    }

    @Override
    @DmsEvent(eventName = DmsEventName.DOCUMENT_SHARED)
    public void sendDocumentByEmail(Session session,
                                    List<Share> shares,
                                    Map<String, String> recipients,
                                    String subject, String content,
                                    String senderAddress, String senderName,
                                    boolean defaultSender, String password)
        throws DmsKernelException {

        try {
            if(logger.isDebugEnabled()) {
                logger.debug("Submitted recipients: {}", recipients);
                //logger.debug("Submitted doc ids: {}", documentIds);
                logger.debug("Submitted contents is: {}", content);
            }
            List<MultiPartEmail> emails = new ArrayList<>();
            MultiPartEmail email = initShareNotificationEmail(session, recipients,
                    subject, senderAddress, senderName, defaultSender);

            /*
                Build Link List only if attachment shouldn't be built inside !
             */
            String finalContent = content;
            String finalContentPassword = content;

            if(StringUtils.isBlank(content)){
                content = loadDefaultMailTemplate(session);
            }
            if(content.contains("__DOCUMENTSLINKS__")){
                User user = FactoryInstantiator.getInstance()
                        .getAuthenticationSourceFactory().getAuthenticationSource(session.getUserSource())
                        .getUserFactory().getUser(session.getUid());
                Map<String, Object> items = new HashMap<String, Object>();
                //Generate Document Links
                for(Share share: shares){
                    Document doc = dmsFactoryInstantiator.getDocumentFactory()
                            .getDocument(share.getEntity().getUid());
                    if(getSecurityAgent().isReadable(doc, session.getUserName(), session.getUserSource(), session.getGroups())) {
                        DocumentVersion lastVersion = dmsFactoryInstantiator.getDocumentVersionFactory()
                                .getLastDocumentVersion(doc);
                        String md5Pass = "";
                        if (password == null) {
                            password = this.securityFactoryInstantiator.getCredentialsGenerator().generateRandomPassword(8);
                            md5Pass = this.securityFactoryInstantiator.getCredentialsGenerator().generatePassword(password);
                        }
                        DataTransfer transfer = fileTransferController.startDownloadTransactionToken(session,
                                lastVersion.getUid(), md5Pass, share);
                        String publicUrl = ConfigurationManager.getValue(Config.PUBLIC_URL);
                        publicUrl = publicUrl.endsWith("/") ? publicUrl : publicUrl + "/";
                        items.put(publicUrl
                                + "services/rest/share/downloadDocumentByToken?token="
                                + transfer.getDownloadToken(),
                                doc);
                    }else
                        throw new AccessDeniedException();
                }
                finalContent = processShareNotify(finalContent, user, new ArrayList<User>(), items);
                // if needed, build separate email to notify password
                if (password != null) {
                    finalContentPassword = processShareNotifyPassword(content, user, new ArrayList<User>(), items.values(), password);
                    MultiPartEmail emailPassword = initShareNotificationEmail(session, recipients,
                            subject, senderAddress, senderName, defaultSender);
                    emailPassword.setMsg(finalContentPassword);
                    emails.add(emailPassword);
                }
            } else {
                long countSize = 0;
                long maxAttachmentSize = 10000 * 1024;;
                String val = ConfigurationManager.getValue("dms.share.max.attachment.size");
                if(StringUtils.isNotBlank(val)){
                    try{
                        maxAttachmentSize = Long.parseLong(val);
                    }   catch (Exception ex){
                        logger.error("max attachment size not defined as integer. will use default {}", 10);
                    }
                }
                for(Share share: shares){
                    Document d = dmsFactoryInstantiator.getDocumentFactory()
                            .getDocument(share.getEntity().getUid());

                    if(getSecurityAgent().isReadable(d, session.getUserName(), session.getUserSource(), session.getGroups())){
                        DocumentVersion dv = dmsFactoryInstantiator.getDocumentVersionFactory()
                                .getLastDocumentVersion(d);
                        emailFactory.addDocumentVersionAttachment(email, d, dv);
                        logger.debug("added document to mail:  {}", d);
                        countSize += dv.getLength();
                        if(countSize > maxAttachmentSize){
                            throw new ConfigException("MaxAttachmentSizeReached");
                        }
                    } else {
                        throw new AccessDeniedException();
                    }
                }
            }

            email.setMsg(finalContent);
            // add at pole position (to send it first)
            emails.add(0, email);

            for (MultiPartEmail emailToSend : emails) {
                scheduledExecutorService.schedule(new MailTaskRunnable(emailToSend), 1000, TimeUnit.MILLISECONDS);
            }

        }catch (Exception ex){
            logger.error("error while sharing document(s)", ex);
            if(ex instanceof DmsKernelException){
                throw (DmsKernelException)ex;
            } else {
                throw new DmsKernelException(ex);
            }
        }
    }

    public Share createShare(Session session, long entityId, Date expirationDate)
            throws DmsKernelException {
        try {
            Share s = null;
            DMEntity entity = dmsFactoryInstantiator.getDmEntityFactory().getEntity(entityId);
            if (getSecurityAgent().isFullAccess(entity, session.getUserName(), session.getUserSource(), session.getGroups())) {

                if (entity instanceof Document) {
                    //share item
                    s = new Share();
                    s.setCreatorId(session.getUserName());
                    s.setCreatorSource(session.getUserSource());
                    s.setCreationDate(new Date());
                    s.setUpdateDate(s.getCreationDate());
                    s.setRead(true);
                    s.setWrite(false);
                    s.setFullAccess(false);
                    s.setNotify(true);
                    s.setShareStatus(ShareStatus.ACTIVE);
                    s.setType(ShareType.EXTERNAL);
                    s.setExpirationDate(expirationDate);

                    s.setEntity((DMEntityImpl) entity);

                    s = shareFactory.saveShare(s);
                }
            }
            return s;
        } catch (Exception e) {
            throw new DmsKernelException(e);
        }
    }

    @Override
    public List<MailContact> searchContact(Session session, String searchQuery){
        return mailContactFactory.searchContact(searchQuery);
    }


    private String processShareNotify(String template, User user,
                                      List<User> recipients,
                                      Map<String, Object> links) throws Exception {


        String tplContent = null;

        if(template != null){
            tplContent = template;
        } else {
            ITemplate mailTemplate = templateProvider.getDefaultTemplate(TemplateType.SHARE_MAIL);
            tplContent = mailTemplate.getContent();
        }

        //Generate final mail content
        InputStream inputStream = MailShareController.class.getClassLoader().getResourceAsStream("templates/default-documents-links.html");
        String forEachTemplate = Pattern.quote(IOUtils.toString(inputStream));
        String finalContent = tplContent.replaceAll("__DOCUMENTSLINKS__", forEachTemplate);

        Map<String, Object> items = new HashMap<String, Object>();
        items.put("sender", user);
        items.put("recipients", recipients);
        items.put("links", links);

        return templateProcessor.processStringTemplateToString(finalContent, items);
    }

    private String processShareNotifyPassword(String template, User user,
                                              List<User> recipients,
                                              Collection<Object> docs, String password) throws Exception {


        String tplContent = null;

        if(template != null){
            tplContent = template;
        } else {
            ITemplate mailTemplate = templateProvider.getDefaultTemplate(TemplateType.SHARE_MAIL_PASSWORD);
            tplContent = mailTemplate.getContent();
        }

        //Generate final mail content
        InputStream inputStream = MailShareController.class.getClassLoader().getResourceAsStream("templates/default-external-share-password.html");
        String forEachTemplate = Pattern.quote(IOUtils.toString(inputStream));
        String finalContent = tplContent.replaceAll("__DOCUMENTSLINKS__", forEachTemplate);

        Map<String, Object> items = new HashMap<String, Object>();
        items.put("sender", user);
        items.put("recipients", recipients);
        items.put("docs", docs);
        items.put("password", password);

        return templateProcessor.processStringTemplateToString(finalContent, items);
    }

    public String loadDefaultMailTemplate(Session session) throws Exception {
        ITemplate mailTemplate = templateProvider.getDefaultTemplate(TemplateType.SHARE_MAIL);
        if(mailTemplate != null){
            return mailTemplate.getContent();
        } else {
            return "<html><body>__DOCUMENTSLINKS__</body></html>";
        }
    }

    @Override
    public void scheduleMailSend(MailDescriptor mailDescriptor) throws Exception {
        Email email = emailFactory.getEmailObjectFromDescriptor(mailDescriptor);
        scheduledExecutorService.schedule(new MailTaskRunnable(email), 1000, TimeUnit.MILLISECONDS);
    }
}
