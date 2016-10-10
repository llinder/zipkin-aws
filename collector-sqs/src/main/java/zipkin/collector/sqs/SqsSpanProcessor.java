/**
 * Copyright 2016 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.collector.sqs;

import com.amazonaws.AmazonClientException;
import com.amazonaws.handlers.AsyncHandler;
import com.amazonaws.services.sqs.AmazonSQSAsync;
import com.amazonaws.services.sqs.model.DeleteMessageResult;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.amazonaws.util.Base64;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import zipkin.Codec;
import zipkin.Component;
import zipkin.collector.Collector;
import zipkin.internal.Nullable;
import zipkin.storage.Callback;


final class SqsSpanProcessor implements Closeable, Component {

  private static final Logger logger = Logger.getLogger(SqsSpanProcessor.class.getName());

  private final AmazonSQSAsync client;
  private final Collector collector;
  private final String queueUrl;
  private final int waitTimeSeconds;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicReference<CheckResult> status = new AtomicReference<>(CheckResult.OK);

  SqsSpanProcessor(AmazonSQSAsync client, Collector collector, String queueUrl,
      int waitTimeSeconds) {
    this.client = client;
    this.collector = collector;
    this.queueUrl = queueUrl;
    this.waitTimeSeconds = waitTimeSeconds;
  }

  @Override public CheckResult check() {
    return status.get();
  }

  @Override public void close() throws IOException {
    if (closed.getAndSet(true)) throw new IllegalStateException("SQS processor is already closed.");
    client.shutdown();
  }

  SqsSpanProcessor run() {
    // don't throw an exception here since this might be run from a receive callback.
    if (closed.get()) return null;

    ReceiveMessageRequest request = new ReceiveMessageRequest(queueUrl)
        .withWaitTimeSeconds(waitTimeSeconds);

    client.receiveMessageAsync(request,
        new AsyncHandler<ReceiveMessageRequest, ReceiveMessageResult>() {
          @Override public void onError(Exception exception) {
            if (exception instanceof AmazonClientException) {
              // TODO should we ever give up if its retryable?
              if (((AmazonClientException)exception).isRetryable()) {
                run();
              }
            } else {
              logger.log(Level.WARNING, "receive failed", exception);
              status.set(CheckResult.failed(exception));
            }
          }

          @Override
          public void onSuccess(ReceiveMessageRequest request, ReceiveMessageResult result) {
            process(result.getMessages());
            status.lazySet(CheckResult.OK);
            run();
          }
        });

    return this;
  }

  private void process(final List<Message> messages) {
    for (Message message : messages) {
      byte[] spans = Base64.decode(message.getBody());
      collector.acceptSpans(spans, Codec.THRIFT, new Callback<Void>() {
        @Override public void onSuccess(@Nullable Void value) {
          delete(message);
        }
        @Override public void onError(Throwable t) {
          // don't delete messages. this will allow accept calls retry once the
          // messages are marked visible by sqs.
          logger.log(Level.WARNING, "collector accept failed", t);
        }
      });
    }
  }

  private Future<DeleteMessageResult> delete(Message message) {
    // client will buffer deletes on its own to minimize API calls.
    return client.deleteMessageAsync(queueUrl, message.getReceiptHandle());
  }

}
