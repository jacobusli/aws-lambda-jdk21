package com.jacobus.lambda.user;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jacobus.common.db.DbConfig;
import com.jacobus.common.db.mapper.UserMapper;
import com.jacobus.common.model.User;
import com.jacobus.lambda.user.dto.UserRequest;
import org.apache.ibatis.session.SqlSession;

import java.util.Map;

// Lambda ハンドラー：POST /user でユーザー情報を返す
public class UserHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Map<String, String> RESPONSE_HEADERS = Map.of(
            "Content-Type", "application/json"
    );

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            UserRequest request = OBJECT_MAPPER.readValue(event.getBody(), UserRequest.class);

            try (SqlSession session = DbConfig.getSqlSessionFactory().openSession()) {
                UserMapper mapper = session.getMapper(UserMapper.class);
                User user = mapper.findById(request.userId());

                if (user == null) {
                    return response(404, "{\"error\":\"User not found\"}");
                }

                return response(200, OBJECT_MAPPER.writeValueAsString(user));
            }
        } catch (Exception e) {
            return response(500, "{\"error\":\"Internal server error\"}");
        }
    }

    private APIGatewayProxyResponseEvent response(int statusCode, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(RESPONSE_HEADERS)
                .withBody(body);
    }
}
