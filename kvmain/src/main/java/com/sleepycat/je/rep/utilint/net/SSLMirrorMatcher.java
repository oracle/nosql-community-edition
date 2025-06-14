/*-
 * Copyright (C) 2002, 2025, Oracle and/or its affiliates. All rights reserved.
 *
 * This file was distributed by Oracle as part of a version of Oracle NoSQL
 * Database made available at:
 *
 * http://www.oracle.com/technetwork/database/database-technologies/nosqldb/downloads/index.html
 *
 * Please see the LICENSE file included in the top-level directory of the
 * appropriate version of Oracle NoSQL Database for a copy of the license and
 * additional information.
 */

package com.sleepycat.je.rep.utilint.net;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;

import java.io.InputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.auth.x500.X500Principal;

import com.sleepycat.je.rep.ReplicationSSLConfig;
import com.sleepycat.je.rep.net.InstanceContext;
import com.sleepycat.je.rep.net.InstanceLogger;
import com.sleepycat.je.rep.net.InstanceParams;

/**
 * Common base class for mirror comparisons.  Supports both authenticator and
 * host verifier implementations.
 */

class SSLMirrorMatcher {

    private final InstanceContext context;
    private final boolean clientMode;
    private final InstanceLogger logger;
    private final KeyStoreCache keyStoreCache;

    /*
     * The Principal that represents us when in the expected peer's ssl mode.
     */
    private volatile Principal ourPrincipal;

    /**
     * Construct an SSLMirrorMatcher
     *
     * @param params The instantiation parameters.
     * @param clientMode set to true if the matcher will be evaluated
     * as a client that has a server as a peer, or false if it will be
     * evaluated as a server that has received a connection from a client.
     * @throws IllegalArgumentException if the instance cannot be created due
     * to a problem related to the input parameters
     */
    public SSLMirrorMatcher(InstanceParams params, boolean clientMode)
        throws IllegalArgumentException {

        context = params.getContext();
        this.clientMode = clientMode;
        logger = context.getLoggerFactory().getLogger(getClass());
        try {
            keyStoreCache = new KeyStoreCache(
                SSLChannelFactory.getKeyStoreName(context),
                this::determinePrincipal, logger);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException(
                "Problem with keystore: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                "Problem reading keystore: " + e, e);
        }
    }

    /**
     * Checks whether the SSL session peer's certificate DN matches our own.
     *
     * @param sslSession the SSL session that has been established with a peer
     * @return true if the peer's certificate DN matches ours
     */
    public boolean peerMatches(SSLSession sslSession) {
        final Principal checkedOurPrincipal = getOurPrincipal();
        if (checkedOurPrincipal == null) {
            return false;
        }

        /*
         * Get the peer principal, which should also be an X500Principal.
         * We validate that here.
         */
        Principal peerPrincipal = null;
        try {
            peerPrincipal = sslSession.getPeerPrincipal();
        } catch (SSLPeerUnverifiedException pue) {
            logger.log(
                FINE,
                () -> String.format("Error getting peer principal: %s", pue));
            return false;
        }

        if (peerPrincipal == null ||
            ! (peerPrincipal instanceof X500Principal)) {
            if (logger.isLoggable(INFO)) {
                logger.log(
                    INFO,
                    String.format("Unable to attempt peer validation - " +
                                  "peer Principal is: ", peerPrincipal));
            }
            return false;
        }

        if (checkedOurPrincipal.equals(peerPrincipal)) {
            return true;
        } else {
            if (logger.isLoggable(FINE)) {
                logger.log(
                    FINE,
                    String.format("Principal does not match: " +
                                  "local=%s, peer=%s",
                                  checkedOurPrincipal, peerPrincipal));
            }
            return false;
        }
    }

    /**
     * Attempt to determine the Principal that we take on when connecting in
     * client or server context based on the ReplicationNetworkConfig. If we
     * are unable to determine that principal, throw IllegalArgumentException.
     */
    private void determinePrincipal(InputStream inputStream)
        throws IllegalArgumentException
    {

        final ReplicationSSLConfig config =
            (ReplicationSSLConfig) context.getRepNetConfig();

        /*
         * Determine what alias would be used.  It is allowable for this to be
         * null.
         */
        String aliasProp = clientMode ?
            config.getSSLClientKeyAlias() :
            config.getSSLServerKeyAlias();

        final KeyStore keyStore = SSLChannelFactory.readKeyStore(
            context, inputStream);

        if (aliasProp == null || aliasProp.isEmpty()) {
            /* Since we weren't told which one to use, there better be
             * only one option, or this might behave unexpectedly. */
            try {
                if (keyStore.size() < 1) {
                    logger.log(INFO, "KeyStore is empty");
                    throw new IllegalArgumentException(
                        "Unable to determine a local principal for" +
                        " comparison with peer principals");
                } else if (keyStore.size() > 1) {
                    logger.log(INFO, "KeyStore has multiple entries but no " +
                               "alias was specified.  Using the first one " +
                               "available.");
                }
                final Enumeration<String> e = keyStore.aliases();
                aliasProp = e.nextElement();
            } catch (KeyStoreException kse) {
                throw new IllegalArgumentException(
                    "Error accessing aliases from the keystore", kse);
            }
        }

        Certificate cert = null;
        try {
            cert = keyStore.getCertificate(aliasProp);
        } catch (KeyStoreException kse) {
            /* Shouldn't be possible */
            throw new IllegalArgumentException(
                "Error accessing certificate with alias " + aliasProp +
                " from the keystore", kse);
        }

        if (cert == null) {
            logger.log(INFO, "No certificate for alias " + aliasProp +
                       " found in KeyStore");
            throw new IllegalArgumentException(
                "Unable to find a certificate in the keystore");
        }

        if (!(cert instanceof X509Certificate)) {
            logger.log(INFO, "The certificate for alias " + aliasProp +
                       " is not an X509Certificate.");
            throw new IllegalArgumentException(
                "Unable to find a valid certificate in the keystore");
        }

        final X509Certificate x509Cert = (X509Certificate) cert;
        ourPrincipal = x509Cert.getSubjectX500Principal();
    }

    private Principal getOurPrincipal() {
        keyStoreCache.check();
        return ourPrincipal;
    }
}
