package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.translate.LlmService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TarotReadingAgentTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private Map<String,Object> input(String spread) {
        var cards=TarotReadingCatalogue.positions(spread).stream().map(p->Map.<String,Object>of("position",p.get("id"),"cardId","major-09","orientation","reversed","themeName","星尘档案","themeMeaning","从安静里看清方向")).toList();
        return Map.of("spread",TarotReadingCatalogue.spread(spread),"cards",cards,"question","如何整理123456789012345这个项目？");
    }
    private Map<String,Object> clone(Map<String,Object> raw) { return mapper.convertValue(raw,Map.class); }
    @Test void exactContractForEachSpreadAndCodePointLengths() {
        for(String spread:List.of("single","triangle","choice")) {
            var input=input(spread);var reading=TarotTestSupport.reading(input);
            assertSame(reading,TarotReadingAgent.validate(reading,input));
            var extra=clone(reading);extra.put("unexpected",true);assertThrows(IllegalArgumentException.class,()->TarotReadingAgent.validate(extra,input));
            var mismatch=clone(reading);((Map<String,Object>)((List<?>)mismatch.get("cards")).get(0)).put("orientation","upright");assertThrows(IllegalArgumentException.class,()->TarotReadingAgent.validate(mismatch,input));
            var nested=clone(reading);((Map<String,Object>)nested.get("guidance")).put("unknown",1);assertThrows(IllegalArgumentException.class,()->TarotReadingAgent.validate(nested,input));
            var unicode=clone(reading);unicode.put("title","🌙".repeat(24));TarotReadingAgent.validate(unicode,input);unicode.put("title","🌙".repeat(25));assertThrows(IllegalArgumentException.class,()->TarotReadingAgent.validate(unicode,input));
            var length=clone(reading);length.put("overview","短");assertThrows(IllegalArgumentException.class,()->TarotReadingAgent.validate(length,input));
        }
    }
    @Test void redactsNumbersAndRetriesInvalidOutputsAtMostThreeTimes() throws Exception {
        var llm=mock(LlmService.class);var runtime=TarotTestSupport.enabled();var input=input("single");
        when(runtime.matchmakingLlmUrl()).thenReturn("http://localhost/provider");when(runtime.matchmakingLlmKey()).thenReturn("test");when(runtime.matchmakingLlmModel()).thenReturn("test");when(runtime.matchmakingLlmProtocol()).thenReturn("openai");
        when(llm.completeWithConfig(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyInt())).thenReturn("{}","[]",mapper.writeValueAsString(TarotTestSupport.reading(input)));
        var agent=new TarotReadingAgent(llm,runtime,mapper);assertEquals("月下前行指引",agent.write(input).get("title"));
        var payload=org.mockito.ArgumentCaptor.forClass(String.class);verify(llm,times(3)).completeWithConfig(anyString(),anyString(),anyString(),anyString(),anyString(),payload.capture(),anyInt());
        payload.getAllValues().forEach(text->{assertFalse(text.contains("123456789012345"));assertTrue(text.contains("数字已脱敏"));assertTrue(text.contains("星尘档案"));});
        reset(llm);when(llm.completeWithConfig(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyInt())).thenReturn("{}");assertThrows(IllegalStateException.class,()->agent.write(input));verify(llm,times(3)).completeWithConfig(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyInt());
    }
    @Test void rejectsDuplicateKeysAndTrailingObjects() throws Exception {
        var llm=mock(LlmService.class);var runtime=TarotTestSupport.enabled();var input=input("single");
        when(runtime.matchmakingLlmUrl()).thenReturn("local");when(runtime.matchmakingLlmKey()).thenReturn("test");when(runtime.matchmakingLlmModel()).thenReturn("test");when(runtime.matchmakingLlmProtocol()).thenReturn("openai");
        String valid=mapper.writeValueAsString(TarotTestSupport.reading(input));
        when(llm.completeWithConfig(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyInt())).thenReturn(valid.substring(0,valid.length()-1)+",\"title\":\"月下前行指引\"}");
        assertThrows(IllegalStateException.class,()->new TarotReadingAgent(llm,runtime,mapper).write(input));
        when(llm.completeWithConfig(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyInt())).thenReturn(valid+" {}");
        assertThrows(IllegalStateException.class,()->new TarotReadingAgent(llm,runtime,mapper).write(input));
    }
}
