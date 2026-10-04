package com.karewinkcloud.agentweb.client.ui

import com.karewinkcloud.agentweb.client.R.string as S
import com.karewinkcloud.agentweb.client.core.ComfyJob
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ComfyKaggleLabelsTest {
    private fun labels(locale: String): Map<String, String> {
        val folder = if (locale == "zh") "values-zh" else "values"
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$folder/strings.xml")).getElementsByTagName("string")
        return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
    }
    @Test fun bothLocalesHaveChannelColdStartQueueAndPersonalUseCopy() {
        val zh = labels("zh"); val en = labels("en")
        assertEquals("Kaggle 免费 GPU", zh["channel_kaggle"]); assertEquals("Kaggle free GPU", en["channel_kaggle"])
        assertEquals("正在启动免费 GPU…", zh["status_kaggle_starting"]); assertEquals("Starting free GPU…", en["status_kaggle_starting"])
        assertEquals("仅限个人非商业用途。", zh["channel_kaggle_terms"])
        assertEquals("Personal, non-commercial use only.", en["channel_kaggle_terms"])
        assertEquals("队列第 3 位", String.format(zh.getValue("kaggle_queue_position"), 3))
        assertEquals("Queue position: 3", String.format(en.getValue("kaggle_queue_position"), 3))
    }
    @Test fun startupUsesTheWorkingProgressCopyAndDoesNotLabelCloudAsKaggle() {
        val job = ComfyJob("12345678-1234-1234-1234-123456789012", "qwen", "queued", billingChannel = "kaggle_gpu", workerState = "launching")
        assertEquals(S.status_kaggle_starting, comfyProgressResource(job))
        assertEquals(S.status_kaggle_installing, comfyProgressResource(job.copy(workerStage = "installing")))
        assertEquals(S.status_kaggle_models, comfyProgressResource(job.copy(workerStage = "models")))
        assertEquals(S.status_kaggle_comfy, comfyProgressResource(job.copy(workerStage = "comfy")))
        assertEquals(S.status_queued, comfyProgressResource(job.copy(billingChannel = "cloud_gpu")))
        assertEquals(S.status_running, comfyProgressResource(job.copy(status = "running")))
        assertEquals(S.channel_kaggle, comfyChannelResource("kaggle_gpu"))
        assertEquals(S.channel_kaggle_hint, comfyChannelHintResource("kaggle_gpu"))
        assertEquals(S.channel_unknown, comfyChannelHintResource("future_channel"))
    }
    @Test fun failuresExplainDistinctCausesAndOfferCloudAsAnExplicitChoiceInBothLocales() {
        val mapping = mapOf("launch_failed" to ("launch" to S.error_kaggle_launch), "quota_exhausted" to ("quota" to S.error_kaggle_quota),
            "wait_timeout" to ("wait" to S.error_kaggle_wait), "lease_expired" to ("lease" to S.error_kaggle_lease),
            "worker_lost" to ("worker_lost" to S.error_kaggle_worker_lost),
            "execution_failed" to ("execution" to S.error_kaggle_execution), "unavailable" to ("unavailable" to S.error_kaggle_unavailable))
        mapping.forEach { (code, pair) -> assertEquals(pair.second, comfyErrorResource("kaggle_$code")) }
        for (locale in listOf("en", "zh")) {
            val strings = labels(locale)
            val messages = mapping.values.map { strings.getValue("error_kaggle_${it.first}") }
            assertEquals(7, messages.distinct().size)
            assertTrue(messages.all { it.contains("Comfy Cloud") && it.contains(if (locale == "zh") "可选择" else "can choose") })
        }
    }
}
