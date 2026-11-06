package io.github.taehunkim.malbotdari

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taehunkim.malbotdari.data.prefs.SettingsStore
import io.github.taehunkim.malbotdari.ml.ModelHost
import io.github.taehunkim.ondolsaem.sdk.Decisions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** OndolSaem's real decision model through ModelHost.entail, as file tasks use it (skipped without the model). */
@RunWith(AndroidJUnit4::class)
class DecisionHostTest {
    @Test fun aReceiptIsAReceiptAndATripIsNot() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val host = ModelHost(ctx, SettingsStore(ctx), scope)
            val hyps = listOf("영수증", "receipt").map { Decisions.hypothesis(it) }
            val receipt = host.entail("lunch.jpg\n김밥천국 강남점 카드 결제 영수증 합계 12,000원", hyps)
            assumeTrue("OndolSaem has no decision model", receipt != null)
            val trip = host.entail("plan.txt\n다음 주 제주도 3박 4일 일정, 한라산 등반", hyps)!!
            android.util.Log.i("DecisionHostTest", "receipt ${receipt!!.joinToString()} trip ${trip.joinToString()}")
            assertTrue(Decisions.verdict(receipt.max()) == Decisions.Verdict.YES)
            assertTrue("not a yes (unsure goes to the assistant by default)", Decisions.verdict(trip.max()) != Decisions.Verdict.YES)
        } finally { scope.cancel() }
    }
}
