package com.example.gateway.filter;

import com.example.common.CodeMsg;
import com.example.common.JwtUtil;
import com.example.common.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Gateway JWT 全局过滤器：仅做鉴权头翻译，无业务逻辑。
 *
 * <ol>
 *   <li>无条件剥离请求中的 X-User-Id（防外部伪造）</li>
 *   <li>白名单（/user/login、/user/register）直接放行</li>
 *   <li>校验 Authorization: Bearer &lt;token&gt;，成功后下发 X-User-Id: {userId} 给下游</li>
 *   <li>缺失/无效返回 401 + Result 同构 JSON（CodeMsg.SESSION_ERROR）</li>
 * </ol>
 */
@Component
public class JwtGlobalFilter implements GlobalFilter, Ordered {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final List<String> WHITE_LIST = List.of("/user/login", "/user/register");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // 1. 无条件剥离外部伪造的 X-User-Id
        if (request.getHeaders().containsKey(USER_ID_HEADER)) {
            request = request.mutate().headers(headers -> headers.remove(USER_ID_HEADER)).build();
        }

        // 2. 白名单放行（与 user-service JwtInterceptor 的 excludePathPatterns 对齐）
        if (isWhiteListed(request.getURI().getPath())) {
            return chain.filter(exchange.mutate().request(request).build());
        }

        // 3. 校验 Bearer Token
        String auth = request.getHeaders().getFirst(AUTH_HEADER);
        if (auth == null || !auth.startsWith(BEARER_PREFIX)) {
            return unauthorized(exchange);
        }
        Long userId;
        try {
            userId = JwtUtil.parseUserId(auth.substring(BEARER_PREFIX.length()));
        } catch (Exception e) {
            return unauthorized(exchange);
        }

        // 4. 校验通过，下发内部身份头转发下游
        ServerHttpRequest downstream = request.mutate()
            .header(USER_ID_HEADER, String.valueOf(userId))
            .build();
        return chain.filter(exchange.mutate().request(downstream).build());
    }

    private boolean isWhiteListed(String path) {
        return WHITE_LIST.stream().anyMatch(path::equals);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = serializeErrorBody();
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private byte[] serializeErrorBody() {
        try {
            return objectMapper.writeValueAsBytes(
                Result.error(CodeMsg.SESSION_ERROR.getCode(), CodeMsg.SESSION_ERROR.getMsg()));
        } catch (JsonProcessingException e) {
            // 序列化兜底，保证一定返回 401 JSON
            return ("{\"code\":" + CodeMsg.SESSION_ERROR.getCode()
                + ",\"msg\":\"" + CodeMsg.SESSION_ERROR.getMsg() + "\",\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public int getOrder() {
        return -100; // 早于路由转发过滤器执行
    }
}
