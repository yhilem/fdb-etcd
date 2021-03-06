package fr.pierrezemb.fdb.layer.etcd.grpc;

import etcdserverpb.EtcdIoRpcProto;
import etcdserverpb.WatchGrpc;
import fr.pierrezemb.fdb.layer.etcd.notifier.Notifier;
import fr.pierrezemb.fdb.layer.etcd.store.EtcdRecordLayer;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

public class WatchService extends WatchGrpc.WatchImplBase {
  private static final Logger log = LoggerFactory.getLogger(WatchService.class);
  private final Notifier notifier;
  private final EtcdRecordLayer recordLayer;
  private final Random random;

  public WatchService(EtcdRecordLayer etcdRecordLayer, Notifier notifier) {
    this.recordLayer = etcdRecordLayer;
    this.notifier = notifier;
    random = new Random(System.currentTimeMillis());
  }

  @Override
  public StreamObserver<EtcdIoRpcProto.WatchRequest> watch(StreamObserver<EtcdIoRpcProto.WatchResponse> responseObserver) {

    String tenantId = GrpcContextKeys.TENANT_ID_KEY.get();
    if (tenantId == null) {
      throw new RuntimeException("Auth enabled and tenant not found.");
    }
    return new StreamObserver<EtcdIoRpcProto.WatchRequest>() {

      @Override
      public void onNext(EtcdIoRpcProto.WatchRequest request) {
        log.debug("received a watchRequest {}", request);
        switch (request.getRequestUnionCase()) {

          case CREATE_REQUEST:

            EtcdIoRpcProto.WatchCreateRequest createRequest = request.getCreateRequest();
            if (createRequest.getWatchId() == 0) {
              createRequest = createRequest.toBuilder().setWatchId(random.nextLong()).build();
            }

            handleCreateRequest(createRequest, tenantId, responseObserver);
            break;
          case CANCEL_REQUEST:
            handleCancelRequest(request.getCancelRequest(), tenantId);
            break;
          case PROGRESS_REQUEST:
            log.warn("received a progress request");
            break;
          case REQUESTUNION_NOT_SET:
            log.warn("received an empty watch request");
            break;
        }
      }

      @Override
      public void onError(Throwable t) {

      }

      @Override
      public void onCompleted() {

      }
    };
  }

  private void handleCancelRequest(EtcdIoRpcProto.WatchCancelRequest cancelRequest, String tenantId) {
    log.info("cancel watch");
    this.recordLayer.deleteWatch(tenantId, cancelRequest.getWatchId());
  }

  private void handleCreateRequest(EtcdIoRpcProto.WatchCreateRequest createRequest, String tenantId, StreamObserver<EtcdIoRpcProto.WatchResponse> responseObserver) {

    this.recordLayer.put(tenantId, createRequest);
    log.info("successfully registered new Watch");
    notifier.watch(tenantId, createRequest.getWatchId(), event -> {
      log.info("inside WatchService");
      try {
        responseObserver
          .onNext(EtcdIoRpcProto.WatchResponse.newBuilder()
            .addEvents(event)
            .setWatchId(createRequest.getWatchId())
            .build());
      } catch (StatusRuntimeException e) {
        if (e.getStatus().equals(Status.CANCELLED)) {
          log.warn("connection was closed");
          return;
        }

        log.error("cought an error writing response: {}", e.getMessage());
      }
    });
  }
}
