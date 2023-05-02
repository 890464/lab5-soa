package soa.camel

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.apache.camel.ProducerTemplate
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.model.dataformat.JsonLibrary
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import soa.camel.Router.MagicNumbers.stringLength
import soa.camel.Router.MagicNumbers.value

@SpringBootApplication
class Application

fun main(vararg args: String) {
    runApplication<Application>(*args)
}

const val DIRECT_ROUTE = "direct:twitter"
const val COUNT_ROUTE = "direct:extractor"
const val LOG_ROUTE = "direct:log"
const val INDEX_VIEW = "index"
const val MAX = 7
@Controller
class SearchController(private val producerTemplate: ProducerTemplate) {
    @RequestMapping("/")
    fun index() = INDEX_VIEW

    @RequestMapping(value = ["/search"])
    @ResponseBody
    fun search(@RequestParam("q") q: String?): Any =
        producerTemplate.requestBodyAndHeader(DIRECT_ROUTE, "mandalorian", "keywords", q)
}

@Component
class Router(meterRegistry: MeterRegistry) : RouteBuilder() {

    private val perKeywordMessages = TaggedCounter("per-keyword-messages", "keyword", meterRegistry)
    object MagicNumbers {
        const val value = 8
        const val stringLength = 4
    }
    override fun configure() {
        from(DIRECT_ROUTE)
            .process { exchange ->
                val keyword: String = exchange.getIn()
                    .getHeader("keywords") as? String ?: ""
                val (max, remain) = keyword.split(" ").partition { it.startsWith("max:") }
                exchange.getIn().setHeader("keywords", remain.joinToString(" "))
                exchange.getIn().setHeader("count", value)
                max.firstOrNull()
                    ?.drop(stringLength)
                    ?.toIntOrNull()
                    ?.let { count ->
                        exchange.getIn().setHeader("count", count)
                    }
            }
            .toD("twitter-search:\${header.keywords}")
            .wireTap(LOG_ROUTE)
            .wireTap(COUNT_ROUTE)

        from(LOG_ROUTE)
            .marshal().json(JsonLibrary.Gson)
            .to("file://log?fileName=\${date:now:yyyy/MM/dd/HH-mm-ss.SSS}.json")

        from(COUNT_ROUTE)
            .split(body())
            .process { exchange ->
                val keyword = exchange.getIn().getHeader("keywords") as? String
                keyword?.split(" ")?.map { perKeywordMessages.increment(it) }
            }
    }
}

class TaggedCounter(private val name: String, private val tagName: String, private val registry: MeterRegistry) {
    private val counters: MutableMap<String, Counter> = HashMap()
    fun increment(tagValue: String) {
        counters.getOrPut(tagValue) {
            Counter.builder(name).tags(tagName, tagValue).register(registry)
        }.increment()
    }
}
