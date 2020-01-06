package uk.gov.ida.stuboidcbroker.services.oidcprovider;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.id.Audience;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.id.Subject;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.claims.AccessTokenHash;
import com.nimbusds.openid.connect.sdk.claims.CodeHash;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.token.OIDCTokens;
import net.minidev.json.JSONObject;
import uk.gov.ida.stuboidcbroker.configuration.StubOidcBrokerConfiguration;
import uk.gov.ida.stuboidcbroker.rest.Urls;
import uk.gov.ida.stuboidcbroker.services.shared.RedisService;

import javax.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.Date;

public class TokenHandlerService {

    private static final String ISSUER = "stub-oidc-op";
    private RedisService redisService;
    private final StubOidcBrokerConfiguration configuration;

    public TokenHandlerService(RedisService redisService, StubOidcBrokerConfiguration configuration) {
        this.redisService = redisService;
        this.configuration = configuration;
    }

    public JWT generateAndGetIdToken(AuthorizationCode authCode, AuthenticationRequest authRequest, AccessToken accessToken) {
        CodeHash cHash = CodeHash.compute(authCode, JWSAlgorithm.RS256);
        AccessTokenHash aHash = AccessTokenHash.compute(accessToken, JWSAlgorithm.RS256);
        IDTokenClaimsSet idTokenClaimsSet = new IDTokenClaimsSet(
                new Issuer(ISSUER),
                new Subject(),
                Collections.singletonList(new Audience(authRequest.getClientID())),
                new Date(),
                new Date());
        idTokenClaimsSet.setCodeHash(cHash);
        idTokenClaimsSet.setNonce(authRequest.getNonce());
        idTokenClaimsSet.setAccessTokenHash(aHash);
        JWTClaimsSet jwtClaimsSet;
        try {
            jwtClaimsSet = idTokenClaimsSet.toJWTClaimsSet();
        } catch (ParseException e) {
            throw new RuntimeException("Unable to parse IDTokenClaimsSet to JWTClaimsSet", e);
        }

        RSAKey signingKey = createSigningKey();
        JWSHeader jwsHeader = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build();
        SignedJWT idToken;

        try {
            JWSSigner signer = new RSASSASigner(signingKey);
            idToken = new SignedJWT(jwsHeader, jwtClaimsSet);
            idToken.sign(signer);
        } catch (JOSEException e) {
            throw new RuntimeException();
        }

        storeTokens(idToken, accessToken, authCode);

        return idToken;
    }

    public OIDCTokens getTokens(AuthorizationCode authCode) {

        String tokens = redisService.get(authCode.getValue());

        JSONObject jsonObject;
        try {
            jsonObject = new JSONObject(JSONObjectUtils.parse(tokens));
            return OIDCTokens.parse(jsonObject);
        } catch (java.text.ParseException | ParseException e) {
            throw new RuntimeException(e);
        }
    }

    public String getVerifiableCredential(AccessToken accessToken) {

        URI userInfoURI = UriBuilder.fromUri(configuration.getIdpURI())
                .path(Urls.IDP.CREDENTIAL_URI).build();

        HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .header("Authorization", accessToken.toAuthorizationHeader())
                .uri(userInfoURI)
                .build();

        HttpResponse<String> responseBody;
        try {
            responseBody = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return responseBody.body();
    }

    private void storeTokens(JWT idToken, AccessToken accessToken, AuthorizationCode authCode) {

        OIDCTokens oidcTokens = new OIDCTokens(idToken, accessToken, null);

        redisService.set(authCode.getValue(), oidcTokens.toJSONObject().toJSONString());
    }

    private RSAKey createSigningKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("123").generate();
        } catch (JOSEException e) {
            throw new RuntimeException("Unable to create RSA key");
        }
    }
}