package process;

import communication.GlobalSignatures;
import core.message.node.NodeMessage;
import data.Command;
import data.Ledger;
import data.ServerNode;
import data.ServerNodeVerify;
import data.NodeDataRequest;
import data.verification.VerifyRequest;
import org.apache.log4j.Logger;
import security.KeyLoader;
import util.*;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;

public class Verification {
    private final static Logger logger = Logger.getLogger(Verification.class);

    private Verification() {}

    public static void nodeVerifyTransaction(SelectionKey key, Map<String, String> requestMessage,
                                             Command command, String nodeName, PrivateKey privateKey,
                                             Map<String, NodeDataRequest> nodeDataRequest) throws IOException {
        final VerifyRequest verifyRequest = new VerifyRequest(requestMessage);
        Path path = Resource.getNodeCertificate(verifyRequest.getNodeName());

        if (path != null) {
            final PublicKey nodePublicKey = KeyLoader.loadPublicKey(path);
            final ServerNode serverNode = (ServerNode) key.attachment();
            final SocketChannel nodeClient = (SocketChannel) key.channel();
            final Selector selector = key.selector();

            if (GlobalSignatures.isNodeVerifiedTransactionSignatureValid(nodePublicKey,
                    verifyRequest.getSignature(),
                    verifyRequest.getGuid(),
                    verifyRequest.getSenderKey(),
                    verifyRequest.getReceiverKey(),
                    verifyRequest.getAmountString(),
                    verifyRequest.getSenderSignature(),
                    verifyRequest.getTimestamp(),
                    verifyRequest.getHash(),
                    verifyRequest.getNodeName())) {

                final float amount = Float.parseFloat(verifyRequest.getAmountString());
                final float userBalance = Ledger.getUserBalance(nodeName, verifyRequest.getSenderKey()).calculateBalance();

                if (userBalance >= amount) {
                    if (command.equals(Command.VERIFY)) {
                        final String newHash = Transactions.generateTransactionHash(verifyRequest.getSenderKey(),
                                verifyRequest.getReceiverKey(),
                                verifyRequest.getGuid(),
                                verifyRequest.getAmountString(),
                                verifyRequest.getSenderSignature(),
                                verifyRequest.getTimestamp(),
                                verifyRequest.getNodeName());


                        if (verifyRequest.getHash().equals(newHash)
                                && !nodeDataRequest.containsKey(verifyRequest.getSenderKey())
                                && !nodeDataRequest.containsKey(verifyRequest.getReceiverKey())) {
                            final String message = Messages.generateNodeVerifyMessage(privateKey,
                                    Command.VERIFY_OK.name(),
                                    verifyRequest.getGuid(),
                                    verifyRequest.getSenderKey(),
                                    verifyRequest.getReceiverKey(),
                                    verifyRequest.getAmountString(),
                                    verifyRequest.getSenderSignature(),
                                    verifyRequest.getTimestamp(),
                                    verifyRequest.getHash(),
                                    nodeName);

                            nodeDataRequest.put(verifyRequest.getSenderKey(), new NodeDataRequest(1, null));
                            nodeDataRequest.put(verifyRequest.getReceiverKey(), new NodeDataRequest(1, null));
                            nodeClient.register(selector, SelectionKey.OP_WRITE, new NodeMessage(message, serverNode));
                        } else {
                            final String message = Messages
                                    .generateNodeVerifyErrorMessage(privateKey, nodeName, Command.VERIFY_ERR);
                            nodeClient.register(selector, SelectionKey.OP_WRITE, new NodeMessage(message, serverNode));
                        }
                    } else {
                        // Verify ok
                        final String signature = verifyRequest.getNodeName()
                                .concat(":").concat(verifyRequest.getSignature());

                        final NodeDataRequest dataRequest = nodeDataRequest.get(verifyRequest.getSenderKey());
                        if (dataRequest != null) {
                            dataRequest.addDataAndIncrementOkResponse(signature);
                        }

                        nodeClient.register(selector, SelectionKey.OP_READ, serverNode);
                    }
                } else {
                    triggerErrorMessage(privateKey, nodeName, serverNode, key);
                }
            } else {
                triggerErrorMessage(privateKey, nodeName, serverNode, key);
            }
        } else {
            logger.error("Error - Node certificate not found.");

            key.cancel();
            key.channel().close();
        }
    }

    private static void triggerErrorMessage(PrivateKey privateKey, String nodeName, ServerNode serverNode, SelectionKey key) {
        final String message = Messages
                .generateNodeVerifyErrorMessage(privateKey, nodeName, Command.VERIFY_ERR);

        logger.error(message);
        try {
            key.channel().register(key.selector(), SelectionKey.OP_WRITE, new NodeMessage(message, serverNode));
        } catch (ClosedChannelException ex) {
            logger.error(ex);
            key.cancel();
        }
    }

    public static void processVerifyError(SelectionKey key, Map<String, String> requestMessage, String command, Map<String, NodeDataRequest> nodeDataRequest) throws IOException {
        final String nodeName = requestMessage.get("nodename");
        final String signature = requestMessage.get("signature");
        final Path path = Resource.getNodeCertificate(nodeName);
        final PublicKey nodePublicKey = KeyLoader.loadPublicKey(path);
        final Selector selector = key.selector();
         if (path != null) {
            if (Signatures.verifyNodeSignature(nodePublicKey, command, nodeName, signature)) {
                final ServerNodeVerify serverNodeVerify = (ServerNodeVerify) key.attachment();
                final SocketChannel nodeClient = (SocketChannel) key.channel();

                final NodeDataRequest dataRequest = nodeDataRequest.get(serverNodeVerify.getSenderKey());
                if (dataRequest != null) {
                    dataRequest.incrementErrorResponse();
                }

                nodeClient.register(selector, SelectionKey.OP_READ, new ServerNode(serverNodeVerify.getName(),
                        serverNodeVerify.getIp(),
                        serverNodeVerify.getPort()));

                logger.info("dead");
            } else {
                logger.error("Error - invalid signature.");
            }
        } else {
            logger.error("Error - Node certificate not found.");

            key.cancel();
            key.channel().close();
        }
    }

    public static void sendVerificationRequests(SelectionKey key,
                                         List<ServerNode> serverNodes,
                                         PrivateKey privateKey,
                                         Map<String, NodeDataRequest> nodeDataRequestMap,
                                         Thread thread,
                                         String nodeName,
                                         String command,
                                         String guid,
                                         String senderKey,
                                         String receiverKey,
                                         String amount,
                                         String senderSignature,
                                         String timestamp,
                                         String hash) {
        final Selector selector = key.selector();
        final NodeDataRequest nodeDataRequest = new NodeDataRequest(serverNodes.size(), thread);
        nodeDataRequestMap.put(senderKey, nodeDataRequest);
        serverNodes.forEach(serverNode -> {
            final String message = Messages.generateNodeVerifyMessage(privateKey, command,
                    guid, senderKey, receiverKey, amount, senderSignature, timestamp, hash, nodeName);
            try {
                serverNode.getSocketChannel().register(selector, SelectionKey.OP_WRITE, new NodeMessage(message, new ServerNodeVerify(serverNode, senderKey)));

                logger.info(serverNode.toString().concat(" - Request Sent"));
            } catch (ClosedChannelException ex) {

                logger.error(serverNode.toString().concat(" - Error while sending request"));

                NodeDataRequest requestData = nodeDataRequestMap.get(senderKey);
                if (requestData != null) {
                    requestData.incrementErrorResponse();
                }
            }
        });
    }
}
