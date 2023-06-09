package com.example.demostatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.weaver.ast.Or;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.access.StateMachineAccess;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.state.State;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.statemachine.support.StateMachineInterceptor;
import org.springframework.statemachine.support.StateMachineInterceptorAdapter;
import org.springframework.statemachine.transition.Transition;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@SpringBootApplication
public class DemoStatusApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoStatusApplication.class, args);
    }

}

enum OrderEvents {
    FULLFILL,
    PAY,
    CANCEL
}

enum OrderStates {
    SUBMITTED,
    PAID,
    FULFILLED,
    CANCELED
}
@Service
@AllArgsConstructor
class OrderService {
    private final OrderRepository orderRepository;
    private final StateMachineFactory<OrderStates, OrderEvents> factory;

//    OrderService(OrderRepository orderRepository, StateMachineFactory<OrderStates, OrderEvents> factory) {
//        this.orderRepository = orderRepository;
//        this.factory = factory;
//    }

    Order create(Date when) {
        return this.orderRepository.save(new Order(when, OrderStates.SUBMITTED));
    }

    StateMachine<OrderStates, OrderEvents> pay(Long orderId, String paymentConfirmationNumber) {
        StateMachine<OrderStates, OrderEvents> sm = this.build(orderId);

        Message<OrderEvents> paymentMessage = MessageBuilder.withPayload(OrderEvents.PAY)
                .setHeader(ORDER_ID_HEADER, orderId)
                .setHeader("paymentConfirmationNumber", paymentConfirmationNumber)
                .build();

        sm.sendEvent(paymentMessage);
        return sm;
    }

    StateMachine<OrderStates, OrderEvents> fulfill(Long orderId) {
        StateMachine<OrderStates, OrderEvents> sm = this.build(orderId);

        Message<OrderEvents> fulfillmentMessage = MessageBuilder.withPayload(OrderEvents.FULLFILL)
                .setHeader(ORDER_ID_HEADER, orderId)
                .build();

        sm.sendEvent(fulfillmentMessage);
        return sm;
    }

    private static final String ORDER_ID_HEADER = "orderId";
    private StateMachine<OrderStates, OrderEvents> build(Long orderId) {
        Optional<Order> orderById = this.orderRepository.findById(orderId);
        Order order = orderById.get();

        StateMachine<OrderStates, OrderEvents> sm = this.factory.getStateMachine(String.valueOf(order.getId()));
        sm.stop();
        sm.getStateMachineAccessor()
                .doWithAllRegions(sma -> {
                    sma.addStateMachineInterceptor(new StateMachineInterceptorAdapter<>() {
                        @Override
                        public void preStateChange(State<OrderStates, OrderEvents> state, Message<OrderEvents> message, Transition<OrderStates, OrderEvents> transition, StateMachine<OrderStates, OrderEvents> stateMachine, StateMachine<OrderStates, OrderEvents> rootStateMachine) {
                            Optional.ofNullable(message).ifPresent(msg ->
                                    Optional.ofNullable((Long)msg.getHeaders().getOrDefault(ORDER_ID_HEADER, -1L))
                                            .ifPresent(orderId1 -> {
                                                Order order1 = orderRepository.getById(orderId1);
                                                order1.setOrderState(state.getId());
                                                orderRepository.save(order1);
                                            }));
                        }
                    });

                    sma.resetStateMachine(new DefaultStateMachineContext<>(
                            OrderStates.valueOf(order.getOrderState().name()), null, null, null));
                });
        sm.start();
        return sm;
    }
}

@Slf4j
@Component
class Runner implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) throws Exception {

        Order order = this.orderService.create(new Date());

        StateMachine<OrderStates, OrderEvents> paymentStateMachine = orderService.pay(order.getId(), UUID.randomUUID().toString());
        log.info("after calling pay() : {}", paymentStateMachine.getState().getId().name());

        StateMachine<OrderStates, OrderEvents> fulfilledStateMachine = orderService.fulfill(order.getId());
        log.info("after calling fulfilledStateMachine() : {}", fulfilledStateMachine.getState().getId().name());


    }

    private final OrderService orderService;
    Runner(OrderService orderService) {
        this.orderService = orderService;
    }

//    private final StateMachineFactory<OrderStates, OrderEvents> factory;

//    Runner(StateMachineFactory<OrderStates, OrderEvents> factory) {
//        this.factory = factory;
//    }

//    @Override
//    public void run(ApplicationArguments args) throws Exception {
//
//        Order order -
//
//        Long orderId = 1212L;
//        StateMachine<OrderStates, OrderEvents> machine = this.factory.getStateMachine(String.valueOf(orderId));
//        machine.getExtendedState().getVariables().putIfAbsent("orderId", orderId);
//        machine.start();
//        log.info("current state : {}", machine.getState().getId().name());
//        machine.sendEvent(OrderEvents.PAY);
//        log.info("current state : {}", machine.getState().getId().name());
//        Message<OrderEvents> eventMessage = MessageBuilder
//                .withPayload(OrderEvents.FULLFILL)
//                .setHeader("a", "b")
//                .build();
//        machine.sendEvent(eventMessage);
//        log.info("current state : {}", machine.getState().getId().name());
//    }
}


interface OrderRepository extends JpaRepository<Order, Long> {
}
@Entity (name = "ORDERS")
@Data
@NoArgsConstructor
@AllArgsConstructor
class Order {
    @Id
    @GeneratedValue
    private Long id;
    private Date datetime;
    private String state;

    public Order(Date d, OrderStates os) {
        this.datetime = d;
        setOrderState(os);
    }

    public OrderStates getOrderState() {
        return OrderStates.valueOf(this.state);
    }

    public void setOrderState(OrderStates s) {
        this.state = s.name();
    }
}




@Slf4j
@Configuration
@EnableStateMachineFactory
class SimpleEnumStatMachineConfiguration extends StateMachineConfigurerAdapter<OrderStates, OrderEvents> {

    @Override
    public void configure(StateMachineTransitionConfigurer<OrderStates, OrderEvents> transitions) throws Exception {
        transitions
                .withExternal().source(OrderStates.SUBMITTED).target(OrderStates.PAID).event(OrderEvents.PAY)
                .and()
                .withExternal().source(OrderStates.PAID).target(OrderStates.FULFILLED).event(OrderEvents.FULLFILL)
                .and()
                .withExternal().source(OrderStates.SUBMITTED).target(OrderStates.CANCELED).event(OrderEvents.CANCEL);
    }

    @Override
    public void configure(StateMachineStateConfigurer<OrderStates, OrderEvents> states) throws Exception {
        states.withStates()
                .initial(OrderStates.SUBMITTED)
//                .stateEntry(OrderStates.SUBMITTED, context -> {
//                    Long orderId = (Long) context.getExtendedState().getVariables().getOrDefault("orderId", -1L);
//                    log.info("orderId is {}.", orderId);
//                    log.info("entering submitted state !");
//                })
                .state(OrderStates.PAID)
                .end(OrderStates.FULFILLED)
                .end(OrderStates.CANCELED);
    }

    @Override
    public void configure(StateMachineConfigurationConfigurer<OrderStates, OrderEvents> config) throws Exception {
        StateMachineListenerAdapter<OrderStates, OrderEvents> adapter = new StateMachineListenerAdapter<OrderStates, OrderEvents>(){
            @Override
            public void stateChanged(State<OrderStates, OrderEvents> from, State<OrderStates, OrderEvents> to) {
                log.info("stateChanged(from: {} -> to: {}", from + "", to.getId().name());
            }
        };
        config.withConfiguration()
                .autoStartup(false)
                .listener(adapter);
    }
}
