package process;

import communication.GlobalSignatures;
import core.message.node.NodeMessage;
import data.*;
import org.apache.log4j.Logger;
import security.KeyLoader;
import util.Messages;
import util.Resource;
import util.Signatures;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;

public class Record {
    private final static Logger logger = Logger.getLogger(Record.class);

    private Record() {

    }

    public static void processRecordRequest(SelectionKey key, Map<String, String> requestMessage, PrivateKey privateKey, String nodeName, ServerNodes serverNodes, Map<String, NodeDataRequest> dataMap) throws IOException {
        final String senderNodeName = requestMessage.get("nodename");
        final String signature = requestMessage.get("signature");
        Path path = Resource.getNodeCertificate(senderNodeName);

        if (path != null) {
            final SocketChannel client = (SocketChannel) key.channel();
            final Selector selector = key.selector();
            final Transaction transaction = Transaction.mapResponseToTransaction(requestMessage);
            final PublicKey senderNodePublicKey = KeyLoader.loadPublicKey(path);
            final ServerNode serverNode = serverNodes.getNodeByName(senderNodeName);
            final String message;

            if (GlobalSignatures.isRecordSignatureValid(senderNodePublicKey, signature, transaction)) {
                Ledger.addTransaction(transaction, nodeName);

                message = Messages.generateNodeRecordOkMessage(privateKey, transaction, nodeName);
            } else {
                // Invalid signature
                message = Messages.generateNodeRecordErrorMessage(privateKey, transaction, nodeName);
            }

            dataMap.remove(transaction.getSenderPublicKey());
            dataMap.remove(transaction.getRecipientPublicKey());
            client.register(selector, SelectionKey.OP_WRITE, new NodeMessage(message, serverNode));
        } else {
            key.cancel();
            key.channel().close();
        }
    }

    public static void triggerTransactionConfirmation(Selector selector,
                                                      List<ServerNode> connectedNodes,
                                                      String nodeName,
                                                      String message,
                                                      Map<String, NodeDataRequest> nodeDataRequestMap,
                                                      String senderKey,
                                                      Thread thread) {

        if (!connectedNodes.isEmpty()) {
            final NodeDataRequest nodeDataRequest = new NodeDataRequest(connectedNodes.size(), thread);
            nodeDataRequestMap.put(senderKey, nodeDataRequest);
            connectedNodes.forEach(serverNode -> {
                try {
                    serverNode.getSocketChannel().register(selector, SelectionKey.OP_WRITE, new NodeMessage(message, serverNode));
                } catch (IOException e) {
                    logger.info(nodeName.concat(" - Error when sending confirm request to ").concat(serverNode.toString()));

                    final NodeDataRequest dataRequest = nodeDataRequestMap.get(senderKey);
                    if (dataRequest != null) {
                        dataRequest.incrementErrorResponse();
                    }
                }
            });
        }
    }

    public static void processRecordResponse(SelectionKey key, Map<String, String> requestMessage, PrivateKey privateKey, Map<String, NodeDataRequest> dataMap) throws IOException {
        final String senderNodeName = requestMessage.get("nodename");
        final String signature = requestMessage.get("signature");
        final String senderKey = requestMessage.get("senderkey");
        final SocketChannel client = (SocketChannel) key.channel();
        final Selector selector = key.selector();
        final ServerNode serverNode = (ServerNode) key.attachment();

        logger.info(requestMessage);


        final Path path = Resource.getNodeCertificate(senderNodeName);
        if (path != null) {
            final NodeDataRequest dataRequest = dataMap.get(senderKey);

            if (dataRequest != null) {
                if (Signatures.verifySignature(KeyLoader.loadPublicKey(path), senderKey, signature)) {
                    dataMap.get(senderKey).incrementOkResponse();
                } else {
                    dataMap.get(senderKey).incrementErrorResponse();
                }
            }

            client.register(selector, SelectionKey.OP_READ, serverNode);
        } else {
            key.cancel();
            key.channel().close();
        }
    }
}
