package com.stackingrule.contentcenter;


import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.stackingrule.contentcenter.dao.content.ShareMapper;
import com.stackingrule.contentcenter.domain.dto.user.UserDTO;
import com.stackingrule.contentcenter.domain.entity.content.Share;

import com.stackingrule.contentcenter.feignclient.TestBaiduFeignClient;
import com.stackingrule.contentcenter.feignclient.TestUserCenterFeignClient;
import com.stackingrule.contentcenter.sentineltest.TestControllerBlockHandlerClass;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.stream.messaging.Source;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.GetMapping;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Slf4j
@RestController
public class TestController {

    // @Autowired(required = false)
    @Resource
    private ShareMapper shareMapper;

    @Autowired
    private DiscoveryClient discoveryClient;

    @Autowired
    private TestUserCenterFeignClient testUserCenterFeignClient;

    @Autowired
    private TestBaiduFeignClient testBaiduFeignClient;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private Source source;

    @GetMapping("/test")
    public List<Share> testInsert() {
        // 1. 做插入
        Share share = new Share();
        share.setCreateTime(new Date());
        share.setUpdateTime(new Date());
        share.setTitle("xxx");
        share.setCover("xxx");
        share.setAuthor("Allen");
        share.setBuyCount(1);

        this.shareMapper.insertSelective(share);

        // 2. 做查询: 查询当前数据库所有的share  select * from share ;
        List<Share> shares = this.shareMapper.selectAll();

        return shares;
    }

    /**
     * <h2>测试服务发现，证明内容中心能找到用户中心</h2>
     * @return
     */
    @GetMapping("/test2")
    public List<ServiceInstance> setDiscoveryClient() {

        // 查询指定服务的所有实例信息
        return this.discoveryClient.getInstances("user-center");

    }

    @GetMapping("/test-get")
    public UserDTO query(UserDTO userDTO) {
        return testUserCenterFeignClient.query(userDTO);
    }

    @GetMapping("/baidu")
    public String baiduIndex() {
        return this.testBaiduFeignClient.index();
    }


    @GetMapping("test-hot")
    @SentinelResource("hot")
    public String testHot(@RequestParam(required = false) String a,
                          @RequestParam(required = false) String b) {
        return a + " " + b;
    }



    @GetMapping("test-add-flow-rule")
    public String testHot() {
        this.initFlowQpsRule();
        return "success";
    }

    private void initFlowQpsRule() {
        List<FlowRule> rules = new ArrayList<>();
        FlowRule rule = new FlowRule("/shares/1");

        rule.setCount(20);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setLimitApp("default");
        rules.add(rule);
        FlowRuleManager.loadRules(rules);
    }


    @GetMapping("/test-sentinel-api")
    public String testSentinelAPI(
            @RequestParam(required = false) String a) {

        String resourceName = "test-sentinel-api";
        ContextUtil.enter(resourceName, "test-wfw");

        // 定义一个sentinel保护的资源，名称是test-sentinel-api
        Entry entry = null;
        try {

            entry = SphU.entry(resourceName);
            // 被保护的业务逻辑
            if (StringUtils.isBlank(a)) {
                throw new IllegalArgumentException("a不能为空");
            }
            return a;
        }
        // 如果被保护的资源被限流或者降级了，就会抛BlockException
        catch (BlockException e) {
            log.warn("限流，或者降级了", e);
            return "限流，或者降级了";
        } catch (IllegalArgumentException e2) {
            // 统计IllegalArgumentException【发生的次数、发生占比...】
            Tracer.trace(e2);
            return "参数非法！";
        } finally {
            if (entry != null) {
                // 退出entry
                entry.exit();
            }
            ContextUtil.exit();
        }

    }

    @GetMapping("/test-sentinel-resource")
    @SentinelResource(value = "test-sentinel-api",
            blockHandler = "block",
            blockHandlerClass = TestControllerBlockHandlerClass.class,
            fallback = "fallback")
    public String testSentinelResource(@RequestParam(required = false) String a) {
        if (StringUtils.isBlank(a)) {
            throw new IllegalArgumentException("a cannot be blank.");
        }
        return a;
    }


    /**
     * <h2>处理降级</h2>
     * - sentinel 1.6 可以处理Throwable
     * @param a
     * @return
     */
    public String fallback(String a) {
        return "限流，或者降级了 fallback";
    }


    @GetMapping("/test-rest-template-sentinel/{userId}")
    public UserDTO test(@PathVariable Integer userId) {
        //
        return this.restTemplate
                .getForObject("http://user-center/users/{userId}",
                UserDTO.class, userId);
    }

    @GetMapping("/test-stream")
    public String testStream() {
        this.source.output()
                .send(
                        MessageBuilder
                            .withPayload("消息体")
                            .build()
                );

        return "success";
    }


    /**
     * RestTemplate实现Token传递，exchange
     * @param userId
     * @return
     */
    @GetMapping("/tokenRelay/{userId}")
    public ResponseEntity<UserDTO> tokenRelay(@PathVariable Integer userId, HttpServletRequest request) {
        String token = request.getHeader("X-Token");
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Token", token);

        return this.restTemplate
                .exchange(
                        "http://user-center/users/{userId}",
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        UserDTO.class,
                        userId
                );
    }

    @Value("${your.configuration}")
    private String yourConfiguration;

    @GetMapping("/test-config")
    public String testConfiguration() {
        return this.yourConfiguration;
    }



}
