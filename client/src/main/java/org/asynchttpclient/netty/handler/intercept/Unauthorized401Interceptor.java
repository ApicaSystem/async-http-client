/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty.handler.intercept;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.Request;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.ChannelState;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.ntlm.NtlmEngine;
import org.asynchttpclient.spnego.SpnegoEngine;
import org.asynchttpclient.spnego.SpnegoEngineException;
import org.asynchttpclient.uri.Uri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaderNames.WWW_AUTHENTICATE;
import static org.asynchttpclient.Dsl.realm;
import static org.asynchttpclient.util.AuthenticatorUtils.NEGOTIATE;
import static org.asynchttpclient.util.AuthenticatorUtils.getHeaderWithPrefix;
import static org.asynchttpclient.util.MiscUtils.withDefault;

public class Unauthorized401Interceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(Unauthorized401Interceptor.class);

    private final ChannelManager channelManager;
    private final NettyRequestSender requestSender;

    Unauthorized401Interceptor(ChannelManager channelManager, NettyRequestSender requestSender) {
        this.channelManager = channelManager;
        this.requestSender = requestSender;
    }

    public boolean exitAfterHandling401(Channel channel, NettyResponseFuture<?> future, HttpResponse response, Request request, Realm realm, HttpRequest httpRequest) {
        if (realm == null) {
            LOGGER.debug("Can't handle 401 as there's no realm");
            return false;
        }

        if (future.isAndSetInAuth(true)) {
            LOGGER.info("Can't handle 401 as auth was already performed");
            return false;
        }

        List<String> wwwAuthHeaders = response.headers().getAll(WWW_AUTHENTICATE);

        if (wwwAuthHeaders.isEmpty()) {
            LOGGER.info("Can't handle 401 as response doesn't contain WWW-Authenticate headers");
            return false;
        }

        // FIXME what's this???
        future.setChannelState(ChannelState.NEW);
        HttpHeaders requestHeaders = new DefaultHttpHeaders().add(request.getHeaders());

        switch (realm.getScheme()) {
            case BASIC:
                if (getHeaderWithPrefix(wwwAuthHeaders, "Basic") == null) {
                    LOGGER.info("Can't handle 401 with Basic realm as WWW-Authenticate headers don't match");
                    return false;
                }

                if (realm.isUsePreemptiveAuth()) {
                    // FIXME do we need this, as future.getAndSetAuth
                    // was tested above?
                    // auth was already performed, most likely auth
                    // failed
                    LOGGER.info("Can't handle 401 with Basic realm as auth was preemptive and already performed");
                    return false;
                }

                // FIXME do we want to update the realm, or directly
                // set the header?
                Realm newBasicRealm = realm(realm)
                        .setUsePreemptiveAuth(true)
                        .build();
                future.setRealm(newBasicRealm);
                break;

            case DIGEST:
                String digestHeader = getHeaderWithPrefix(wwwAuthHeaders, "Digest");
                if (digestHeader == null) {
                    LOGGER.info("Can't handle 401 with Digest realm as WWW-Authenticate headers don't match");
                    return false;
                }
                Realm newDigestRealm = realm(realm)
                        .setUri(request.getUri())
                        .setMethodName(request.getMethod())
                        .setUsePreemptiveAuth(true)
                        .parseWWWAuthenticateHeader(digestHeader)
                        .build();
                future.setRealm(newDigestRealm);
                break;

            case NTLM:
                String ntlmHeader = getHeaderWithPrefix(wwwAuthHeaders, "NTLM");
                if (ntlmHeader == null) {
                    LOGGER.info("Can't handle 401 with NTLM realm as WWW-Authenticate headers don't match");
                    return false;
                }

                ntlmChallenge(ntlmHeader, requestHeaders, realm, future);
                Realm newNtlmRealm = realm(realm)
                        .setUsePreemptiveAuth(true)
                        .build();
                future.setRealm(newNtlmRealm);
                break;

            case KERBEROS:
            case SPNEGO:
                if (getHeaderWithPrefix(wwwAuthHeaders, NEGOTIATE) == null) {
                    LOGGER.info("Can't handle 401 with Kerberos or Spnego realm as WWW-Authenticate headers don't match");
                    return false;
                }
                try {
                    kerberosChallenge(realm, request, requestHeaders);
                } catch (SpnegoEngineException e) {
                    String ntlmHeader2 = getHeaderWithPrefix(wwwAuthHeaders, "NTLM");
                    if (ntlmHeader2 != null) {
                        LOGGER.warn("Kerberos/Spnego auth failed, proceeding with NTLM");
                        ntlmChallenge(ntlmHeader2, requestHeaders, realm, future);
                        Realm newNtlmRealm2 = realm(realm)
                                .setScheme(AuthScheme.NTLM)
                                .setUsePreemptiveAuth(true)
                                .build();
                        future.setRealm(newNtlmRealm2);
                    } else {
                        requestSender.abort(channel, future, e);
                        return false;
                    }
                }
                break;
            default:
                throw new IllegalStateException("Invalid Authentication scheme " + realm.getScheme());
        }

        final Request nextRequest = future.getCurrentRequest().toBuilder().setHeaders(requestHeaders).build();

        LOGGER.debug("Sending authentication to {}", request.getUri());
        if (future.isKeepAlive() && !HttpUtil.isTransferEncodingChunked(httpRequest) && !HttpUtil.isTransferEncodingChunked(response)) {
            future.setReuseChannel(true);
            requestSender.drainChannelAndExecuteNextRequest(channel, future, nextRequest);
        } else {
            channelManager.closeChannel(channel);
            requestSender.sendNextRequest(nextRequest, future);
        }

        return true;
    }

    private static void ntlmChallenge(String authenticateHeader,
                                      HttpHeaders requestHeaders,
                                      Realm realm,
                                      NettyResponseFuture<?> future) {

        if ("NTLM".equals(authenticateHeader)) {
            // server replied bare NTLM => we didn't preemptively sent Type1Msg
            String challengeHeader = NtlmEngine.INSTANCE.generateType1Msg();
            // FIXME we might want to filter current NTLM and add (leave other
            // Authorization headers untouched)
            requestHeaders.set(AUTHORIZATION, "NTLM " + challengeHeader);
            future.setInAuth(false);

        } else {
            String serverChallenge = authenticateHeader.substring("NTLM ".length()).trim();
            String challengeHeader = NtlmEngine.INSTANCE.generateType3Msg(realm.getPrincipal(), realm.getPassword(),
                    realm.getNtlmDomain(), realm.getNtlmHost(), serverChallenge);
            // FIXME we might want to filter current NTLM and add (leave other
            // Authorization headers untouched)
            requestHeaders.set(AUTHORIZATION, "NTLM " + challengeHeader);
        }
    }

    private static void kerberosChallenge(Realm realm, Request request, HttpHeaders headers) throws SpnegoEngineException {
        Uri uri = request.getUri();
        String host = withDefault(request.getVirtualHost(), uri.getHost());
        String challengeHeader = SpnegoEngine.instance(realm.getPrincipal(),
                realm.getPassword(),
                realm.getServicePrincipalName(),
                realm.getRealmName(),
                realm.isUseCanonicalHostname(),
                realm.getCustomLoginConfig(),
                realm.getLoginContextName()).generateToken(host);
        headers.set(AUTHORIZATION, NEGOTIATE + ' ' + challengeHeader);
    }
}
