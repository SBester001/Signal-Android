package org.thoughtcrime.securesms.jobs;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Pair;

import org.signal.libsignal.metadata.InvalidMetadataMessageException;
import org.signal.libsignal.metadata.InvalidMetadataVersionException;
import org.signal.libsignal.metadata.ProtocolDuplicateMessageException;
import org.signal.libsignal.metadata.ProtocolInvalidKeyException;
import org.signal.libsignal.metadata.ProtocolInvalidKeyIdException;
import org.signal.libsignal.metadata.ProtocolInvalidMessageException;
import org.signal.libsignal.metadata.ProtocolInvalidVersionException;
import org.signal.libsignal.metadata.ProtocolLegacyMessageException;
import org.signal.libsignal.metadata.ProtocolNoSessionException;
import org.signal.libsignal.metadata.ProtocolUntrustedIdentityException;
import org.signal.libsignal.metadata.SelfSendException;
import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.ConversationListActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.attachments.PointerAttachment;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.contactshare.ContactModelMapper;
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil;
import org.thoughtcrime.securesms.crypto.SecurityEvent;
import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil;
import org.thoughtcrime.securesms.crypto.storage.SignalProtocolStoreImpl;
import org.thoughtcrime.securesms.crypto.storage.TextSecureSessionStore;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.GroupReceiptDatabase;
import org.thoughtcrime.securesms.database.MessagingDatabase;
import org.thoughtcrime.securesms.database.MessagingDatabase.InsertResult;
import org.thoughtcrime.securesms.database.MessagingDatabase.SyncMessageId;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.PushDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.groups.GroupMessageProcessor;
import org.thoughtcrime.securesms.jobmanager.JobParameters;
import org.thoughtcrime.securesms.jobmanager.SafeData;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.mms.IncomingMediaMessage;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingExpirationUpdateMessage;
import org.thoughtcrime.securesms.mms.OutgoingMediaMessage;
import org.thoughtcrime.securesms.mms.OutgoingSecureMediaMessage;
import org.thoughtcrime.securesms.mms.QuoteModel;
import org.thoughtcrime.securesms.mms.SlideDeck;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.service.WebRtcCallService;
import org.thoughtcrime.securesms.sms.IncomingEncryptedMessage;
import org.thoughtcrime.securesms.sms.IncomingEndSessionMessage;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.thoughtcrime.securesms.sms.OutgoingEncryptedMessage;
import org.thoughtcrime.securesms.sms.OutgoingEndSessionMessage;
import org.thoughtcrime.securesms.sms.OutgoingTextMessage;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.thoughtcrime.securesms.util.IdentityUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.state.SessionStore;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceTypingMessage;
import org.whispersystems.signalservice.api.messages.calls.AnswerMessage;
import org.whispersystems.signalservice.api.messages.calls.BusyMessage;
import org.whispersystems.signalservice.api.messages.calls.HangupMessage;
import org.whispersystems.signalservice.api.messages.calls.IceUpdateMessage;
import org.whispersystems.signalservice.api.messages.calls.OfferMessage;
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage;
import org.whispersystems.signalservice.api.messages.multidevice.ReadMessage;
import org.whispersystems.signalservice.api.messages.multidevice.RequestMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SentTranscriptMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.api.messages.multidevice.VerifiedMessage;
import org.whispersystems.signalservice.api.messages.shared.SharedContact;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import androidx.work.Data;
import androidx.work.WorkerParameters;

public class PushDecryptJob extends ContextJob {

  private static final long serialVersionUID = 2L;

  public static final String TAG = PushDecryptJob.class.getSimpleName();

  private static final String KEY_MESSAGE_ID     = "message_id";
  private static final String KEY_SMS_MESSAGE_ID = "sms_message_id";

  private long messageId;
  private long smsMessageId;

  public PushDecryptJob(@NonNull Context context, @NonNull WorkerParameters workerParameters) {
    super(context, workerParameters);
  }

  public PushDecryptJob(Context context) {
    this(context, -1);
  }

  public PushDecryptJob(Context context, long pushMessageId) {
    this(context, pushMessageId, -1);
  }

  public PushDecryptJob(Context context, long pushMessageId, long smsMessageId) {
    super(context, JobParameters.newBuilder()
                                .withGroupId("__PUSH_DECRYPT_JOB__")
                                .create());
    this.messageId    = pushMessageId;
    this.smsMessageId = smsMessageId;
  }

  @Override
  protected void initialize(@NonNull SafeData data) {
    messageId    = data.getLong(KEY_MESSAGE_ID);
    smsMessageId = data.getLong(KEY_SMS_MESSAGE_ID);
  }

  @Override
  protected @NonNull Data serialize(@NonNull Data.Builder dataBuilder) {
    return dataBuilder.putLong(KEY_MESSAGE_ID, messageId)
                      .putLong(KEY_SMS_MESSAGE_ID, smsMessageId)
                      .build();
  }

  @Override
  public void onRun() throws NoSuchMessageException {
    synchronized (PushReceivedJob.RECEIVE_LOCK) {
      if (needsMigration()) {
        Log.w(TAG, "Skipping, waiting for migration...");
        postMigrationNotification();
        return;
      }

      PushDatabase          database             = DatabaseFactory.getPushDatabase(context);
      SignalServiceEnvelope envelope             = database.get(messageId);
      Optional<Long>        optionalSmsMessageId = smsMessageId > 0 ? Optional.of(smsMessageId) : Optional.absent();

      handleMessage(envelope, optionalSmsMessageId);
      database.delete(messageId);
    }
  }

  @Override
  public boolean onShouldRetry(Exception exception) {
    return false;
  }

  @Override
  public void onCanceled() {

  }

  public void processMessage(@NonNull SignalServiceEnvelope envelope) {
    synchronized (PushReceivedJob.RECEIVE_LOCK) {
      if (needsMigration()) {
        Log.w(TAG, "Skipping and storing envelope, waiting for migration...");
        DatabaseFactory.getPushDatabase(context).insert(envelope);
        postMigrationNotification();
        return;
      }

      handleMessage(envelope, Optional.absent());
    }
  }

  private boolean needsMigration() {
    return !IdentityKeyUtil.hasIdentityKey(context) || TextSecurePreferences.getNeedsSqlCipherMigration(context);
  }

  private void postMigrationNotification() {
    NotificationManagerCompat.from(context).notify(494949,
                                                   new NotificationCompat.Builder(context, NotificationChannels.getMessagesChannel(context))
                                                                         .setSmallIcon(R.drawable.icon_notification)
                                                                         .setPriority(NotificationCompat.PRIORITY_HIGH)
                                                                         .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                                                                         .setContentTitle(context.getString(R.string.PushDecryptJob_new_locked_message))
                                                                         .setContentText(context.getString(R.string.PushDecryptJob_unlock_to_view_pending_messages))
                                                                         .setContentIntent(PendingIntent.getActivity(context, 0, new Intent(context, ConversationListActivity.class), 0))
                                                                         .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE)
                                                                         .build());

  }

  private void handleMessage(@NonNull SignalServiceEnvelope envelope, @NonNull Optional<Long> smsMessageId) {
    try {
      GroupDatabase        groupDatabase = DatabaseFactory.getGroupDatabase(context);
      SignalProtocolStore  axolotlStore  = new SignalProtocolStoreImpl(context);
      SignalServiceAddress localAddress  = new SignalServiceAddress(TextSecurePreferences.getLocalNumber(context));
      SignalServiceCipher  cipher        = new SignalServiceCipher(localAddress, axolotlStore, UnidentifiedAccessUtil.getCertificateValidator());

      SignalServiceContent content = cipher.decrypt(envelope);

      if (shouldIgnore(content)) {
        Log.i(TAG, "Ignoring message.");
        return;
      }

      if (content.getDataMessage().isPresent()) {
        SignalServiceDataMessage message        = content.getDataMessage().get();
        boolean                  isMediaMessage = message.getAttachments().isPresent() || message.getQuote().isPresent() || message.getSharedContacts().isPresent();

        if      (message.isEndSession())        handleEndSessionMessage(content, smsMessageId);
        else if (message.isGroupUpdate())       handleGroupMessage(content, message, smsMessageId);
        else if (message.isExpirationUpdate())  handleExpirationUpdate(content, message, smsMessageId);
        else if (isMediaMessage)                handleMediaMessage(content, message, smsMessageId);
        else if (message.getBody().isPresent()) handleTextMessage(content, message, smsMessageId);

        if (message.getGroupInfo().isPresent() && groupDatabase.isUnknownGroup(GroupUtil.getEncodedId(message.getGroupInfo().get().getGroupId(), false))) {
          handleUnknownGroupMessage(content, message.getGroupInfo().get());
        }

        if (message.getProfileKey().isPresent() && message.getProfileKey().get().length == 32) {
          handleProfileKey(content, message);
        }

        if (content.isNeedsReceipt()) {
          handleNeedsDeliveryReceipt(content, message);
        }
      } else if (content.getSyncMessage().isPresent()) {
        TextSecurePreferences.setMultiDevice(context, true);

        SignalServiceSyncMessage syncMessage = content.getSyncMessage().get();

        if      (syncMessage.getSent().isPresent())     handleSynchronizeSentMessage(content, syncMessage.getSent().get());
        else if (syncMessage.getRequest().isPresent())  handleSynchronizeRequestMessage(syncMessage.getRequest().get());
        else if (syncMessage.getRead().isPresent())     handleSynchronizeReadMessage(syncMessage.getRead().get(), content.getTimestamp());
        else if (syncMessage.getVerified().isPresent()) handleSynchronizeVerifiedMessage(syncMessage.getVerified().get());
        else                                           Log.w(TAG, "Contains no known sync types...");
      } else if (content.getCallMessage().isPresent()) {
        Log.i(TAG, "Got call message...");
        SignalServiceCallMessage message = content.getCallMessage().get();

        if      (message.getOfferMessage().isPresent())      handleCallOfferMessage(content, message.getOfferMessage().get(), smsMessageId);
        else if (message.getAnswerMessage().isPresent())     handleCallAnswerMessage(content, message.getAnswerMessage().get());
        else if (message.getIceUpdateMessages().isPresent()) handleCallIceUpdateMessage(content, message.getIceUpdateMessages().get());
        else if (message.getHangupMessage().isPresent())     handleCallHangupMessage(content, message.getHangupMessage().get(), smsMessageId);
        else if (message.getBusyMessage().isPresent())       handleCallBusyMessage(content, message.getBusyMessage().get());
      } else if (content.getReceiptMessage().isPresent()) {
        SignalServiceReceiptMessage message = content.getReceiptMessage().get();

        if      (message.isReadReceipt())     handleReadReceipt(content, message);
        else if (message.isDeliveryReceipt()) handleDeliveryReceipt(content, message);
      } else if (content.getTypingMessage().isPresent()) {
        handleTypingMessage(content, content.getTypingMessage().get());
      } else {
        Log.w(TAG, "Got unrecognized message...");
      }

      if (envelope.isPreKeySignalMessage()) {
        ApplicationContext.getInstance(context).getJobManager().add(new RefreshPreKeysJob(context));
      }
    } catch (ProtocolInvalidVersionException e) {
      Log.w(TAG, e);
      handleInvalidVersionMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (ProtocolInvalidMessageException  e) {
      Log.w(TAG, e);
      handleCorruptMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    }
    catch (ProtocolInvalidKeyIdException | ProtocolInvalidKeyException | ProtocolUntrustedIdentityException e) {
      Log.w(TAG, e);
      handleCorruptMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (StorageFailedException e) {
      Log.w(TAG, e);
      handleCorruptMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (ProtocolNoSessionException e) {
      Log.w(TAG, e);
      handleNoSessionMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (ProtocolLegacyMessageException e) {
      Log.w(TAG, e);
      handleLegacyMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (ProtocolDuplicateMessageException e) {
      Log.w(TAG, e);
      handleDuplicateMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (InvalidMetadataVersionException | InvalidMetadataMessageException e) {
      Log.w(TAG, e);
    } catch (SelfSendException e) {
      Log.i(TAG, "Dropping UD message from self.");
    }
  }

  private void handleCallOfferMessage(@NonNull SignalServiceContent content,
                                      @NonNull OfferMessage message,
                                      @NonNull Optional<Long> smsMessageId)
  {
    Log.w(TAG, "handleCallOfferMessage...");

    if (smsMessageId.isPresent()) {
      SmsDatabase database = DatabaseFactory.getSmsDatabase(context);
      database.markAsMissedCall(smsMessageId.get());
    } else {
      Intent intent = new Intent(context, WebRtcCallService.class);
      intent.setAction(WebRtcCallService.ACTION_INCOMING_CALL);
      intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, Address.fromExternal(context, content.getSender()));
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_DESCRIPTION, message.getDescription());
      intent.putExtra(WebRtcCallService.EXTRA_TIMESTAMP, content.getTimestamp());

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
      else                                                context.startService(intent);
    }
  }

  private void handleCallAnswerMessage(@NonNull SignalServiceContent content,
                                       @NonNull AnswerMessage message)
  {
    Log.i(TAG, "handleCallAnswerMessage...");
    Intent intent = new Intent(context, WebRtcCallService.class);
    intent.setAction(WebRtcCallService.ACTION_RESPONSE_MESSAGE);
    intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
    intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, Address.fromExternal(context, content.getSender()));
    intent.putExtra(WebRtcCallService.EXTRA_REMOTE_DESCRIPTION, message.getDescription());

    context.startService(intent);
  }

  private void handleCallIceUpdateMessage(@NonNull SignalServiceContent content,
                                          @NonNull List<IceUpdateMessage> messages)
  {
    Log.w(TAG, "handleCallIceUpdateMessage... " + messages.size());
    for (IceUpdateMessage message : messages) {
      Intent intent = new Intent(context, WebRtcCallService.class);
      intent.setAction(WebRtcCallService.ACTION_ICE_MESSAGE);
      intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, Address.fromExternal(context, content.getSender()));
      intent.putExtra(WebRtcCallService.EXTRA_ICE_SDP, message.getSdp());
      intent.putExtra(WebRtcCallService.EXTRA_ICE_SDP_MID, message.getSdpMid());
      intent.putExtra(WebRtcCallService.EXTRA_ICE_SDP_LINE_INDEX, message.getSdpMLineIndex());

      context.startService(intent);
    }
  }

  private void handleCallHangupMessage(@NonNull SignalServiceContent content,
                                       @NonNull HangupMessage message,
                                       @NonNull Optional<Long> smsMessageId)
  {
    Log.i(TAG, "handleCallHangupMessage");
    if (smsMessageId.isPresent()) {
      DatabaseFactory.getSmsDatabase(context).markAsMissedCall(smsMessageId.get());
    } else {
      Intent intent = new Intent(context, WebRtcCallService.class);
      intent.setAction(WebRtcCallService.ACTION_REMOTE_HANGUP);
      intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, Address.fromExternal(context, content.getSender()));

      context.startService(intent);
    }
  }

  private void handleCallBusyMessage(@NonNull SignalServiceContent content,
                                     @NonNull BusyMessage message)
  {
    Intent intent = new Intent(context, WebRtcCallService.class);
    intent.setAction(WebRtcCallService.ACTION_REMOTE_BUSY);
    intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
    intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, Address.fromExternal(context, content.getSender()));

    context.startService(intent);
  }

  private void handleEndSessionMessage(@NonNull SignalServiceContent content,
                                       @NonNull Optional<Long>       smsMessageId)
  {
    SmsDatabase         smsDatabase         = DatabaseFactory.getSmsDatabase(context);
    IncomingTextMessage incomingTextMessage = new IncomingTextMessage(Address.fromExternal(context, content.getSender()),
                                                                      content.getSenderDevice(),
                                                                      content.getTimestamp(),
                                                                      "", Optional.absent(), 0,
                                                                      content.isNeedsReceipt());

    Long threadId;

    if (!smsMessageId.isPresent()) {
      IncomingEndSessionMessage incomingEndSessionMessage = new IncomingEndSessionMessage(incomingTextMessage);
      Optional<InsertResult>    insertResult              = smsDatabase.insertMessageInbox(incomingEndSessionMessage);

      if (insertResult.isPresent()) threadId = insertResult.get().getThreadId();
      else                          threadId = null;
    } else {
      smsDatabase.markAsEndSession(smsMessageId.get());
      threadId = smsDatabase.getThreadIdForMessage(smsMessageId.get());
    }

    if (threadId != null) {
      SessionStore sessionStore = new TextSecureSessionStore(context);
      sessionStore.deleteAllSessions(content.getSender());

      SecurityEvent.broadcastSecurityUpdateEvent(context);
      MessageNotifier.updateNotification(context, threadId);
    }
  }

  private long handleSynchronizeSentEndSessionMessage(@NonNull SentTranscriptMessage message)
  {
    SmsDatabase               database                  = DatabaseFactory.getSmsDatabase(context);
    Recipient                 recipient                 = getSyncMessageDestination(message);
    OutgoingTextMessage       outgoingTextMessage       = new OutgoingTextMessage(recipient, "", -1);
    OutgoingEndSessionMessage outgoingEndSessionMessage = new OutgoingEndSessionMessage(outgoingTextMessage);

    long threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);

    if (!recipient.isGroupRecipient()) {
      SessionStore sessionStore = new TextSecureSessionStore(context);
      sessionStore.deleteAllSessions(recipient.getAddress().toPhoneString());

      SecurityEvent.broadcastSecurityUpdateEvent(context);

      long messageId = database.insertMessageOutbox(threadId, outgoingEndSessionMessage,
                                                    false, message.getTimestamp(),
                                                    null);
      database.markAsSent(messageId, true);
    }

    return threadId;
  }

  private void handleGroupMessage(@NonNull SignalServiceContent content,
                                  @NonNull SignalServiceDataMessage message,
                                  @NonNull Optional<Long> smsMessageId)
      throws StorageFailedException
  {
    GroupMessageProcessor.process(context, content, message, false);

    if (message.getExpiresInSeconds() != 0 && message.getExpiresInSeconds() != getMessageDestination(content, message).getExpireMessages()) {
      handleExpirationUpdate(content, message, Optional.absent());
    }

    if (smsMessageId.isPresent()) {
      DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
    }
  }

  private void handleUnknownGroupMessage(@NonNull SignalServiceContent content,
                                         @NonNull SignalServiceGroup group)
  {
    ApplicationContext.getInstance(context)
                      .getJobManager()
                      .add(new RequestGroupInfoJob(context, content.getSender(), group.getGroupId()));
  }

  private void handleExpirationUpdate(@NonNull SignalServiceContent content,
                                      @NonNull SignalServiceDataMessage message,
                                      @NonNull Optional<Long> smsMessageId)
      throws StorageFailedException
  {
    try {
      MmsDatabase          database     = DatabaseFactory.getMmsDatabase(context);
      Recipient            recipient    = getMessageDestination(content, message);
      IncomingMediaMessage mediaMessage = new IncomingMediaMessage(Address.fromExternal(context, content.getSender()),
                                                                   message.getTimestamp(), -1,
                                                                   message.getExpiresInSeconds() * 1000L, true,
                                                                   content.isNeedsReceipt(),
                                                                   Optional.absent(),
                                                                   message.getGroupInfo(),
                                                                   Optional.absent(),
                                                                   Optional.absent(),
                                                                   Optional.absent());

        database.insertSecureDecryptedMessageInbox(mediaMessage, -1);

        DatabaseFactory.getRecipientDatabase(context).setExpireMessages(recipient, message.getExpiresInSeconds());

      if (smsMessageId.isPresent()) {
        DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
      }
    } catch (MmsException e) {
      throw new StorageFailedException(e, content.getSender(), content.getSenderDevice());
    }
  }

  private void handleSynchronizeVerifiedMessage(@NonNull VerifiedMessage verifiedMessage) {
    IdentityUtil.processVerifiedMessage(context, verifiedMessage);
  }

  private void handleSynchronizeSentMessage(@NonNull SignalServiceContent content,
                                            @NonNull SentTranscriptMessage message)
      throws StorageFailedException

  {
    try {
      GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);

      Long threadId;

      if (message.getMessage().isEndSession()) {
        threadId = handleSynchronizeSentEndSessionMessage(message);
      } else if (message.getMessage().isGroupUpdate()) {
        threadId = GroupMessageProcessor.process(context, content, message.getMessage(), true);
      } else if (message.getMessage().isExpirationUpdate()) {
        threadId = handleSynchronizeSentExpirationUpdate(message);
      } else if (message.getMessage().getAttachments().isPresent() || message.getMessage().getQuote().isPresent()) {
        threadId = handleSynchronizeSentMediaMessage(message);
      } else {
        threadId = handleSynchronizeSentTextMessage(message);
      }

      if (message.getMessage().getGroupInfo().isPresent() && groupDatabase.isUnknownGroup(GroupUtil.getEncodedId(message.getMessage().getGroupInfo().get().getGroupId(), false))) {
        handleUnknownGroupMessage(content, message.getMessage().getGroupInfo().get());
      }

      if (message.getMessage().getProfileKey().isPresent()) {
        Recipient recipient = null;

        if      (message.getDestination().isPresent())            recipient = Recipient.from(context, Address.fromExternal(context, message.getDestination().get()), false);
        else if (message.getMessage().getGroupInfo().isPresent()) recipient = Recipient.from(context, Address.fromSerialized(GroupUtil.getEncodedId(message.getMessage().getGroupInfo().get().getGroupId(), false)), false);


        if (recipient != null && !recipient.isSystemContact() && !recipient.isProfileSharing()) {
          DatabaseFactory.getRecipientDatabase(context).setProfileSharing(recipient, true);
        }
      }

      if (threadId != null) {
        DatabaseFactory.getThreadDatabase(getContext()).setRead(threadId, true);
        MessageNotifier.updateNotification(getContext());
      }

      MessageNotifier.setLastDesktopActivityTimestamp(message.getTimestamp());
    } catch (MmsException e) {
      throw new StorageFailedException(e, content.getSender(), content.getSenderDevice());
    }
  }

  private void handleSynchronizeRequestMessage(@NonNull RequestMessage message)
  {
    if (message.isContactsRequest()) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new MultiDeviceContactUpdateJob(getContext(), true));

      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new RefreshUnidentifiedDeliveryAbilityJob(context));
    }

    if (message.isGroupsRequest()) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new MultiDeviceGroupUpdateJob(getContext()));
    }

    if (message.isBlockedListRequest()) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new MultiDeviceBlockedUpdateJob(getContext()));
    }

    if (message.isConfigurationRequest()) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new MultiDeviceConfigurationUpdateJob(getContext(),
                                                                   TextSecurePreferences.isReadReceiptsEnabled(getContext()),
                                                                   TextSecurePreferences.isTypingIndicatorsEnabled(getContext()),
                                                                   TextSecurePreferences.isShowUnidentifiedDeliveryIndicatorsEnabled(getContext())));
    }
  }

  private void handleSynchronizeReadMessage(@NonNull List<ReadMessage> readMessages, long envelopeTimestamp)
  {
    for (ReadMessage readMessage : readMessages) {
      List<Pair<Long, Long>> expiringText = DatabaseFactory.getSmsDatabase(context).setTimestampRead(new SyncMessageId(Address.fromExternal(context, readMessage.getSender()), readMessage.getTimestamp()), envelopeTimestamp);
      List<Pair<Long, Long>> expiringMedia = DatabaseFactory.getMmsDatabase(context).setTimestampRead(new SyncMessageId(Address.fromExternal(context, readMessage.getSender()), readMessage.getTimestamp()), envelopeTimestamp);

      for (Pair<Long, Long> expiringMessage : expiringText) {
        ApplicationContext.getInstance(context)
                          .getExpiringMessageManager()
                          .scheduleDeletion(expiringMessage.first, false, envelopeTimestamp, expiringMessage.second);
      }

      for (Pair<Long, Long> expiringMessage : expiringMedia) {
        ApplicationContext.getInstance(context)
                          .getExpiringMessageManager()
                          .scheduleDeletion(expiringMessage.first, true, envelopeTimestamp, expiringMessage.second);
      }
    }

    MessageNotifier.setLastDesktopActivityTimestamp(envelopeTimestamp);
    MessageNotifier.cancelDelayedNotifications();
    MessageNotifier.updateNotification(context);
  }

  private void handleMediaMessage(@NonNull SignalServiceContent content,
                                  @NonNull SignalServiceDataMessage message,
                                  @NonNull Optional<Long> smsMessageId)
      throws StorageFailedException
  {
    notifyTypingStoppedFromIncomingMessage(getMessageDestination(content, message), content.getSender(), content.getSenderDevice());

    try {
      MmsDatabase             database       = DatabaseFactory.getMmsDatabase(context);
      Optional<QuoteModel>    quote          = getValidatedQuote(message.getQuote());
      Optional<List<Contact>> sharedContacts = getContacts(message.getSharedContacts());
      IncomingMediaMessage    mediaMessage   = new IncomingMediaMessage(Address.fromExternal(context, content.getSender()),
                                                                        message.getTimestamp(), -1,
                                                                        message.getExpiresInSeconds() * 1000L, false,
                                                                        content.isNeedsReceipt(),
                                                                        message.getBody(),
                                                                        message.getGroupInfo(),
                                                                        message.getAttachments(),
                                                                        quote,
                                                                        sharedContacts);

      Optional<InsertResult> insertResult = database.insertSecureDecryptedMessageInbox(mediaMessage, -1);

      if (insertResult.isPresent()) {
        List<DatabaseAttachment> attachments = DatabaseFactory.getAttachmentDatabase(context).getAttachmentsForMessage(insertResult.get().getMessageId());

        for (DatabaseAttachment attachment : attachments) {
          ApplicationContext.getInstance(context)
                            .getJobManager()
                            .add(new AttachmentDownloadJob(context, insertResult.get().getMessageId(), attachment.getAttachmentId(), false));
        }

        if (smsMessageId.isPresent()) {
          DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
        }

        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } catch (MmsException e) {
      throw new StorageFailedException(e, content.getSender(), content.getSenderDevice());
    }
  }

  private long handleSynchronizeSentExpirationUpdate(@NonNull SentTranscriptMessage message) throws MmsException {
    MmsDatabase database   = DatabaseFactory.getMmsDatabase(context);
    Recipient   recipient  = getSyncMessageDestination(message);

    OutgoingExpirationUpdateMessage expirationUpdateMessage = new OutgoingExpirationUpdateMessage(recipient,
                                                                                                  message.getTimestamp(),
                                                                                                  message.getMessage().getExpiresInSeconds() * 1000L);

    long threadId  = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
    long messageId = database.insertMessageOutbox(expirationUpdateMessage, threadId, false, null);

    database.markAsSent(messageId, true);

    DatabaseFactory.getRecipientDatabase(context).setExpireMessages(recipient, message.getMessage().getExpiresInSeconds());

    return threadId;
  }

  private long handleSynchronizeSentMediaMessage(@NonNull SentTranscriptMessage message)
      throws MmsException
  {
    MmsDatabase              database       = DatabaseFactory.getMmsDatabase(context);
    Recipient                recipients     = getSyncMessageDestination(message);
    Optional<QuoteModel>     quote          = getValidatedQuote(message.getMessage().getQuote());
    Optional<List<Contact>>  sharedContacts = getContacts(message.getMessage().getSharedContacts());
    OutgoingMediaMessage     mediaMessage   = new OutgoingMediaMessage(recipients, message.getMessage().getBody().orNull(),
                                                                       PointerAttachment.forPointers(message.getMessage().getAttachments()),
                                                                       message.getTimestamp(), -1,
                                                                       message.getMessage().getExpiresInSeconds() * 1000,
                                                                       ThreadDatabase.DistributionTypes.DEFAULT, quote.orNull(),
                                                                       sharedContacts.or(Collections.emptyList()),
                                                                       Collections.emptyList(), Collections.emptyList());

    mediaMessage = new OutgoingSecureMediaMessage(mediaMessage);

    if (recipients.getExpireMessages() != message.getMessage().getExpiresInSeconds()) {
      handleSynchronizeSentExpirationUpdate(message);
    }

    long threadId  = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipients);
    long messageId = database.insertMessageOutbox(mediaMessage, threadId, false, null);

    if (recipients.getAddress().isGroup()) {
      GroupReceiptDatabase receiptDatabase = DatabaseFactory.getGroupReceiptDatabase(context);
      List<Recipient>      members         = DatabaseFactory.getGroupDatabase(context).getGroupMembers(recipients.getAddress().toGroupString(), false);

      for (Recipient member : members) {
        receiptDatabase.setUnidentified(member.getAddress(), messageId, message.isUnidentified(member.getAddress().serialize()));
      }
    }

    database.markAsSent(messageId, true);
    database.markUnidentified(messageId, message.isUnidentified(recipients.getAddress().serialize()));

    for (DatabaseAttachment attachment : DatabaseFactory.getAttachmentDatabase(context).getAttachmentsForMessage(messageId)) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new AttachmentDownloadJob(context, messageId, attachment.getAttachmentId(), false));
    }

    if (message.getMessage().getExpiresInSeconds() > 0) {
      database.markExpireStarted(messageId, message.getExpirationStartTimestamp());
      ApplicationContext.getInstance(context)
                        .getExpiringMessageManager()
                        .scheduleDeletion(messageId, true,
                                          message.getExpirationStartTimestamp(),
                                          message.getMessage().getExpiresInSeconds() * 1000L);
    }

    return threadId;
  }

  private void handleTextMessage(@NonNull SignalServiceContent content,
                                 @NonNull SignalServiceDataMessage message,
                                 @NonNull Optional<Long> smsMessageId)
      throws StorageFailedException
  {
    SmsDatabase database  = DatabaseFactory.getSmsDatabase(context);
    String      body      = message.getBody().isPresent() ? message.getBody().get() : "";
    Recipient   recipient = getMessageDestination(content, message);

    if (message.getExpiresInSeconds() != recipient.getExpireMessages()) {
      handleExpirationUpdate(content, message, Optional.absent());
    }

    Long threadId;

    if (smsMessageId.isPresent() && !message.getGroupInfo().isPresent()) {
      threadId = database.updateBundleMessageBody(smsMessageId.get(), body).second;
    } else {
      notifyTypingStoppedFromIncomingMessage(recipient, content.getSender(), content.getSenderDevice());

      IncomingTextMessage textMessage = new IncomingTextMessage(Address.fromExternal(context, content.getSender()),
                                                                content.getSenderDevice(),
                                                                message.getTimestamp(), body,
                                                                message.getGroupInfo(),
                                                                message.getExpiresInSeconds() * 1000L,
                                                                content.isNeedsReceipt());

      textMessage = new IncomingEncryptedMessage(textMessage, body);
      Optional<InsertResult> insertResult = database.insertMessageInbox(textMessage);

      if (insertResult.isPresent()) threadId = insertResult.get().getThreadId();
      else                          threadId = null;

      if (smsMessageId.isPresent()) database.deleteMessage(smsMessageId.get());
    }

    if (threadId != null) {
      MessageNotifier.updateNotification(context, threadId);
    }
  }

  private long handleSynchronizeSentTextMessage(@NonNull SentTranscriptMessage message)
      throws MmsException
  {

    Recipient recipient       = getSyncMessageDestination(message);
    String    body            = message.getMessage().getBody().or("");
    long      expiresInMillis = message.getMessage().getExpiresInSeconds() * 1000L;

    if (recipient.getExpireMessages() != message.getMessage().getExpiresInSeconds()) {
      handleSynchronizeSentExpirationUpdate(message);
    }

    long    threadId  = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
    boolean isGroup   = recipient.getAddress().isGroup();

    MessagingDatabase database;
    long              messageId;

    if (isGroup) {
      OutgoingMediaMessage outgoingMediaMessage = new OutgoingMediaMessage(recipient, new SlideDeck(), body, message.getTimestamp(), -1, expiresInMillis, ThreadDatabase.DistributionTypes.DEFAULT, null, Collections.emptyList());
      outgoingMediaMessage = new OutgoingSecureMediaMessage(outgoingMediaMessage);

      messageId = DatabaseFactory.getMmsDatabase(context).insertMessageOutbox(outgoingMediaMessage, threadId, false, null);
      database  = DatabaseFactory.getMmsDatabase(context);

      GroupReceiptDatabase receiptDatabase = DatabaseFactory.getGroupReceiptDatabase(context);
      List<Recipient>      members         = DatabaseFactory.getGroupDatabase(context).getGroupMembers(recipient.getAddress().toGroupString(), false);

      for (Recipient member : members) {
        receiptDatabase.setUnidentified(member.getAddress(), messageId, message.isUnidentified(member.getAddress().serialize()));
      }
    } else {
      OutgoingTextMessage outgoingTextMessage = new OutgoingEncryptedMessage(recipient, body, expiresInMillis);

      messageId = DatabaseFactory.getSmsDatabase(context).insertMessageOutbox(threadId, outgoingTextMessage, false, message.getTimestamp(), null);
      database  = DatabaseFactory.getSmsDatabase(context);
      database.markUnidentified(messageId, message.isUnidentified(recipient.getAddress().serialize()));
    }

    database.markAsSent(messageId, true);

    if (expiresInMillis > 0) {
      database.markExpireStarted(messageId, message.getExpirationStartTimestamp());
      ApplicationContext.getInstance(context)
                        .getExpiringMessageManager()
                        .scheduleDeletion(messageId, isGroup, message.getExpirationStartTimestamp(), expiresInMillis);
    }

    return threadId;
  }

  private void handleInvalidVersionMessage(@NonNull String sender, int senderDevice, long timestamp,
                                           @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp);

      if (insertResult.isPresent()) {
        smsDatabase.markAsInvalidVersionKeyExchange(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsInvalidVersionKeyExchange(smsMessageId.get());
    }
  }

  private void handleCorruptMessage(@NonNull String sender, int senderDevice, long timestamp,
                                    @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp);

      if (insertResult.isPresent()) {
        smsDatabase.markAsDecryptFailed(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsDecryptFailed(smsMessageId.get());
    }
  }

  private void handleNoSessionMessage(@NonNull String sender, int senderDevice, long timestamp,
                                      @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp);

      if (insertResult.isPresent()) {
        smsDatabase.markAsNoSession(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsNoSession(smsMessageId.get());
    }
  }

  private void handleLegacyMessage(@NonNull String sender, int senderDevice, long timestamp,
                                   @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp);

      if (insertResult.isPresent()) {
        smsDatabase.markAsLegacyVersion(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsLegacyVersion(smsMessageId.get());
    }
  }

  @SuppressWarnings("unused")
  private void handleDuplicateMessage(@NonNull String sender, int senderDeviceId, long timestamp,
                                      @NonNull Optional<Long> smsMessageId)
  {
    // Let's start ignoring these now
//    SmsDatabase smsDatabase = DatabaseFactory.getEncryptingSmsDatabase(context);
//
//    if (smsMessageId <= 0) {
//      Pair<Long, Long> messageAndThreadId = insertPlaceholder(masterSecret, envelope);
//      smsDatabase.markAsDecryptDuplicate(messageAndThreadId.first);
//      MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
//    } else {
//      smsDatabase.markAsDecryptDuplicate(smsMessageId);
//    }
  }

  private void handleProfileKey(@NonNull SignalServiceContent content,
                                @NonNull SignalServiceDataMessage message)
  {
    RecipientDatabase database      = DatabaseFactory.getRecipientDatabase(context);
    Address           sourceAddress = Address.fromExternal(context, content.getSender());
    Recipient         recipient     = Recipient.from(context, sourceAddress, false);

    if (recipient.getProfileKey() == null || !MessageDigest.isEqual(recipient.getProfileKey(), message.getProfileKey().get())) {
      database.setProfileKey(recipient, message.getProfileKey().get());
      database.setUnidentifiedAccessMode(recipient, RecipientDatabase.UnidentifiedAccessMode.UNKNOWN);
      ApplicationContext.getInstance(context).getJobManager().add(new RetrieveProfileJob(context, recipient));
    }
  }

  private void handleNeedsDeliveryReceipt(@NonNull SignalServiceContent content,
                                          @NonNull SignalServiceDataMessage message)
  {
    ApplicationContext.getInstance(context)
                      .getJobManager()
                      .add(new SendDeliveryReceiptJob(context, Address.fromExternal(context, content.getSender()), message.getTimestamp()));
  }

  @SuppressLint("DefaultLocale")
  private void handleDeliveryReceipt(@NonNull SignalServiceContent content,
                                     @NonNull SignalServiceReceiptMessage message)
  {
    for (long timestamp : message.getTimestamps()) {
      Log.i(TAG, String.format("Received encrypted delivery receipt: (XXXXX, %d)", timestamp));
      DatabaseFactory.getMmsSmsDatabase(context)
                     .incrementDeliveryReceiptCount(new SyncMessageId(Address.fromExternal(context, content.getSender()), timestamp), System.currentTimeMillis());
    }
  }

  @SuppressLint("DefaultLocale")
  private void handleReadReceipt(@NonNull SignalServiceContent content,
                                 @NonNull SignalServiceReceiptMessage message)
  {
    if (TextSecurePreferences.isReadReceiptsEnabled(context)) {
      for (long timestamp : message.getTimestamps()) {
        Log.i(TAG, String.format("Received encrypted read receipt: (XXXXX, %d)", timestamp));

        DatabaseFactory.getMmsSmsDatabase(context)
                       .incrementReadReceiptCount(new SyncMessageId(Address.fromExternal(context, content.getSender()), timestamp), content.getTimestamp());
      }
    }
  }

  private void handleTypingMessage(@NonNull SignalServiceContent content,
                                   @NonNull SignalServiceTypingMessage typingMessage)
  {
    if (!TextSecurePreferences.isTypingIndicatorsEnabled(context)) {
      return;
    }

    Recipient author = Recipient.from(context, Address.fromExternal(context, content.getSender()), false);

    long threadId;

    if (typingMessage.getGroupId().isPresent()) {
      Address   groupAddress   = Address.fromExternal(context, GroupUtil.getEncodedId(typingMessage.getGroupId().get(), false));
      Recipient groupRecipient = Recipient.from(context, groupAddress, false);

      threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(groupRecipient);
    } else {
      threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(author);
    }

    if (threadId <= 0) {
      Log.w(TAG, "Couldn't find a matching thread for a typing message.");
      return;
    }

    if (typingMessage.isTypingStarted()) {
      Log.d(TAG, "Typing started on thread " + threadId);
      ApplicationContext.getInstance(context).getTypingStatusRepository().onTypingStarted(context,threadId, author, content.getSenderDevice());
    } else {
      Log.d(TAG, "Typing stopped on thread " + threadId);
      ApplicationContext.getInstance(context).getTypingStatusRepository().onTypingStopped(context, threadId, author, content.getSenderDevice(), false);
    }
  }

  private Optional<QuoteModel> getValidatedQuote(Optional<SignalServiceDataMessage.Quote> quote) {
    if (!quote.isPresent()) return Optional.absent();

    if (quote.get().getId() <= 0) {
      Log.w(TAG, "Received quote without an ID! Ignoring...");
      return Optional.absent();
    }

    if (quote.get().getAuthor() == null) {
      Log.w(TAG, "Received quote without an author! Ignoring...");
      return Optional.absent();
    }

    Address       author  = Address.fromExternal(context, quote.get().getAuthor().getNumber());
    MessageRecord message = DatabaseFactory.getMmsSmsDatabase(context).getMessageFor(quote.get().getId(), author);

    if (message != null) {
      Log.i(TAG, "Found matching message record...");

      List<Attachment> attachments = new LinkedList<>();

      if (message.isMms()) {
        attachments = ((MmsMessageRecord) message).getSlideDeck().asAttachments();
      }

      return Optional.of(new QuoteModel(quote.get().getId(), author, message.getBody(), false, attachments));
    }

    Log.w(TAG, "Didn't find matching message record...");
    return Optional.of(new QuoteModel(quote.get().getId(),
                                      author,
                                      quote.get().getText(),
                                      true,
                                      PointerAttachment.forPointers(quote.get().getAttachments())));
  }

  private Optional<List<Contact>> getContacts(Optional<List<SharedContact>> sharedContacts) {
    if (!sharedContacts.isPresent()) return Optional.absent();

    List<Contact> contacts = new ArrayList<>(sharedContacts.get().size());

    for (SharedContact sharedContact : sharedContacts.get()) {
      contacts.add(ContactModelMapper.remoteToLocal(sharedContact));
    }

    return Optional.of(contacts);
  }

  private Optional<InsertResult> insertPlaceholder(@NonNull String sender, int senderDevice, long timestamp) {
    SmsDatabase         database    = DatabaseFactory.getSmsDatabase(context);
    IncomingTextMessage textMessage = new IncomingTextMessage(Address.fromExternal(context, sender),
                                                              senderDevice, timestamp, "",
                                                              Optional.absent(), 0, false);

    textMessage = new IncomingEncryptedMessage(textMessage, "");
    return database.insertMessageInbox(textMessage);
  }

  private Recipient getSyncMessageDestination(SentTranscriptMessage message) {
    if (message.getMessage().getGroupInfo().isPresent()) {
      return Recipient.from(context, Address.fromExternal(context, GroupUtil.getEncodedId(message.getMessage().getGroupInfo().get().getGroupId(), false)), false);
    } else {
      return Recipient.from(context, Address.fromExternal(context, message.getDestination().get()), false);
    }
  }

  private Recipient getMessageDestination(SignalServiceContent content, SignalServiceDataMessage message) {
    if (message.getGroupInfo().isPresent()) {
      return Recipient.from(context, Address.fromExternal(context, GroupUtil.getEncodedId(message.getGroupInfo().get().getGroupId(), false)), false);
    } else {
      return Recipient.from(context, Address.fromExternal(context, content.getSender()), false);
    }
  }

  private void notifyTypingStoppedFromIncomingMessage(@NonNull Recipient conversationRecipient, @NonNull String sender, int device) {
    Recipient author   = Recipient.from(context, Address.fromExternal(context, sender), false);
    long      threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(conversationRecipient);

    if (threadId > 0) {
      Log.d(TAG, "Typing stopped on thread " + threadId + " due to an incoming message.");
      ApplicationContext.getInstance(context).getTypingStatusRepository().onTypingStopped(context, threadId, author, device, true);
    }
  }

  private boolean shouldIgnore(@Nullable SignalServiceContent content) {
    if (content == null) {
      Log.w(TAG, "Got a message with null content.");
      return true;
    }

    Recipient sender = Recipient.from(context, Address.fromExternal(context, content.getSender()), false);

    if (content.getDataMessage().isPresent()) {
      SignalServiceDataMessage message      = content.getDataMessage().get();
      Recipient                conversation = getMessageDestination(content, message);

      if (conversation.isGroupRecipient() && conversation.isBlocked()) {
        return true;
      } else if (conversation.isGroupRecipient()) {
        GroupDatabase    groupDatabase = DatabaseFactory.getGroupDatabase(context);
        Optional<String> groupId       = message.getGroupInfo().isPresent() ? Optional.of(GroupUtil.getEncodedId(message.getGroupInfo().get().getGroupId(), false))
                                                                            : Optional.absent();

        if (groupId.isPresent() && groupDatabase.isUnknownGroup(groupId.get())) {
          return false;
        }

        boolean isTextMessage    = message.getBody().isPresent();
        boolean isMediaMessage   = message.getAttachments().isPresent() || message.getQuote().isPresent() || message.getSharedContacts().isPresent();
        boolean isExpireMessage  = message.isExpirationUpdate();
        boolean isContentMessage = !message.isGroupUpdate() && (isTextMessage || isMediaMessage || isExpireMessage);
        boolean isGroupActive    = groupId.isPresent() && groupDatabase.isActive(groupId.get());
        boolean isLeaveMessage   = message.getGroupInfo().isPresent() && message.getGroupInfo().get().getType() == SignalServiceGroup.Type.QUIT;

        return (isContentMessage && !isGroupActive) || (sender.isBlocked() && !isLeaveMessage);
      } else {
        return sender.isBlocked();
      }
    } else if (content.getCallMessage().isPresent()) {
      return sender.isBlocked();
    }

    return false;
  }

  @SuppressWarnings("WeakerAccess")
  private static class StorageFailedException extends Exception {
    private final String sender;
    private final int senderDevice;

    private StorageFailedException(Exception e, String sender, int senderDevice) {
      super(e);
      this.sender       = sender;
      this.senderDevice = senderDevice;
    }

    public String getSender() {
      return sender;
    }

    public int getSenderDevice() {
      return senderDevice;
    }
  }

}
