package app.aino.mobile.core.call.webrtc

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcPolicyTest {
    @Test
    fun decodesStringAndArrayIceUrls() {
        val api = ApiClient { request ->
            assertEquals("chat/ice-config", request.path)
            ApiResponse(
                200,
                emptyMap(),
                """{"mode":"coturn-rest","allowPublicFallback":false,"expiresAt":2000,"iceServers":[
                  {"urls":"stun:stun.example.test:3478"},
                  {"urls":["turn:turn.example.test:3478","turns:turn.example.test:5349"],"username":"u","credential":"p"},
                  {"urls":"turn:openrelay.metered.ca:443","username":"public","credential":"public"}
                ]}""".toByteArray(),
            )
        }
        val config = IceConfigRepository(api).load()
        assertEquals("coturn-rest", config.mode)
        assertEquals(2, config.iceServers.size)
        assertEquals(listOf("turn:turn.example.test:3478", "turns:turn.example.test:5349"), config.iceServers[1].urls.values)
        assertTrue(hasRealTurn(config))
    }

    @Test
    fun publicTurnPolicyKeepsStunAndRemovesOnlyOpenRelay() {
        val servers = listOf(
            IceServerDto(IceUrls(listOf("stun:stun.example.test", "turn:openrelay.metered.ca:80"))),
            IceServerDto(IceUrls(listOf("turn:turn.aino.test:3478")), "u", "p"),
        )
        val filtered = applyPublicTurnPolicy(servers, false)
        assertEquals(listOf("stun:stun.example.test"), filtered[0].urls.values)
        assertEquals("turn:turn.aino.test:3478", filtered[1].urls.values.single())
    }

    @Test
    fun relayOnlyDropsEveryStunUrl() {
        val relay = relayOnlyServers(
            listOf(
                IceServerDto(IceUrls(listOf("stun:s", "turn:t", "turns:tls"))),
                IceServerDto(IceUrls(listOf("stun:only"))),
            ),
        )
        assertEquals(1, relay.size)
        assertEquals(listOf("turn:t", "turns:tls"), relay.single().urls.values)
    }

    @Test
    fun perfectNegotiationMatchesPoliteCalleeRule() {
        assertEquals(OfferCollisionDecision.Accept, offerCollisionDecision(false, false, true))
        assertEquals(OfferCollisionDecision.Ignore, offerCollisionDecision(false, true, true))
        assertEquals(OfferCollisionDecision.RollbackThenAccept, offerCollisionDecision(true, false, false))
    }

    @Test
    fun candidateBufferPreservesOrderAndDropsEndMarker() {
        val buffer = IceCandidateBuffer()
        buffer.add(BufferedIceCandidate("audio", 0, "candidate-one"))
        buffer.add(null)
        buffer.add(BufferedIceCandidate(null, 1, ""))
        buffer.add(BufferedIceCandidate("video", 1, "candidate-two"))
        assertEquals(2, buffer.size())
        assertEquals(listOf("candidate-one", "candidate-two"), buffer.drain().map { it.candidate })
        assertEquals(0, buffer.size())
    }

    @Test
    fun identifiesRealTurnByModeOrNonPublicUrl() {
        assertTrue(hasRealTurn(IceConfigDto(mode = "cloudflare-calls")))
        assertTrue(hasRealTurn(IceConfigDto(iceServers = listOf(IceServerDto(IceUrls(listOf("turn:private.test")))))))
        assertFalse(hasRealTurn(IceConfigDto(iceServers = listOf(IceServerDto(IceUrls(listOf("turn:openrelay.metered.ca:80")))))))
    }
}