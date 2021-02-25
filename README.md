# 영화 예매

MSA/DDD/Event Storming/EDA 를 포괄하는 분석/설계/구현/운영 전단계를 커버하도록 구성한 프로젝트 과제입니다.

- 체크포인트 : https://workflowy.com/s/assessment-check-po/T5YrzcMewfo4J6LW

# 서비스 시나리오

기능적 요구사항

1. 고객이 영화 및 좌석을 선택하고 예매를 한다. 
1. 고객이 결제를 진행한다.
1. 예매 및 결제가 완료되면 티켓이 생성된다.
1. 영화관에서 나의 예매 정보로 티켓을 수령한다.
1. 티켓 수령 전까지 고객이 예매를 취소할 수 있다. 
1. 예매가 취소되면 결제가 취소된다.
1. 고객이 예매 내역 및 상태를 조회할 수 있다.
1. 티켓 수령 후에 리뷰를 작성할 수 있다.

비기능적 요구사항

1. 트랜잭션
   1. 결제가 되지 않은 예매 건은 아예 예매가 성립되지 않아야 한다. Sync 호출
   2. 리뷰 생성이 되지 않으면 티켓 출력이 불가능하다.
   3. 리뷰가 생성 되지 않은 티켓 건은 아예 프린트가 성립되지 않아야 한다. Sync 호출
1. 장애격리
   1. 예매 상태 업데이트 기능이 수행되지 않더라도 리뷰 작성은 365일 24시간 가능해야하 한다. Async (event-driven), Eventual Consistency
   1. 리뷰시스템이 과중되면 사용자를 잠시동안 받지 않고 티켓 프린트를 잠시후에 하도록 유도한다. Circuit breaker, fallback
   1. mypage의 상태가 업데이트 되지 않더라도 리뷰는 작성이 되어야한다.
1. 성능
   1. 고객이 예매 내역을 my page(프론트엔드)에서 확인할 수 있어야 한다 CQRS
   1. 예매 상태가 바뀔때마다 mypage에서 확인 가능하여야 한다 Event driven

# 분석/설계

## Event Storming 결과

- MSAEz 로 모델링한 이벤트스토밍 결과: http://www.msaez.io/#/storming/R6mhRNYqDQNZGOm0lF9mkOuyQb22/mine/71ff9c1518aee16ab14394848c5ab5f8
![스크린샷 2021-02-23 오후 10 26 44](https://user-images.githubusercontent.com/28583602/108850044-37af4400-7626-11eb-9ffb-9153bafb3a6d.png)


# 구현:

분석/설계 단계에서 도출된 헥사고날 아키텍처에 따라, 각 BC별로 대변되는 마이크로 서비스들을 스프링부트로 구현하였다. 구현한 각 서비스를 로컬에서 실행하는 방법은 아래와 같다 (각자의 포트넘버는 8081 ~ 808n 이다)

```
cd book
mvn spring-boot:run

cd payment
mvn spring-boot:run

cd ticket
mvn spring-boot:run

cd mypage
mvn srping-boot:run

cd review
mvn srping-boot:run
```

## 동기식 호출

분석단계에서의 조건 중 하나로 (ticket)->(review) 간의 호출은 동기식 일관성을 유지하는 트랜잭션으로 처리하기로 하였다.
호출 프로토콜은 이미 앞서 Rest Repository 에 의해 노출되어있는 REST 서비스를 FeignClient 를 이용하여 호출하도록 한다.

- Review 서비스를 호출하기 위하여 Stub과 (FeignClient) 를 이용하여 Service 대행 인터페이스 (Proxy) 를 구현

```
# ReviewService.java

package movie.external;

@FeignClient(name="review", url="http://localhost:8085")
public interface ReviewService {

    @RequestMapping(method= RequestMethod.POST, path="/reviews")
    public void create(@RequestBody Review review);

}
```

- Print 직후(@PostUpdate) 결제를 요청하도록 처리

```
# Ticket.java (Entity)

    @PostPersist
    public void onPostUpdate(){
        if("Printed".equals(status)){
            Printed printed = new Printed();
            BeanUtils.copyProperties(this, printed);
            printed.setStatus("Printed");
            printed.publishAfterCommit();
            
            movie.external.Review review = new movie.external.Review();
        
            // mappings goes here
            review.setBookingId(printed.getBookingId());
            review.setStatus("Waiting Review");
            TicketApplication.applicationContext.getBean(movie.external.ReviewService.class)
                .create(review);

        }
    }
```

- 동기식 호출에서는 호출 시간에 따른 타임 커플링이 발생하며, Review 시스템이 장애가 나면 Ticekt 출력도 불가능 하다는 것을 확인


- Review 서비스를 잠시 내려놓음 (ctrl+c)

1. Print 처리

<img width="1052" alt="스크린샷 2021-02-23 오후 10 36 22" src="https://user-images.githubusercontent.com/28583602/108851240-90331100-7627-11eb-93f1-4f8e82440ee2.png">


2. Review서비스 재기동
```
cd ../review
mvn spring-boot:run
```

3. Print 처리

<img width="822" alt="스크린샷 2021-02-23 오후 10 40 37" src="https://user-images.githubusercontent.com/28583602/108851787-28c99100-7628-11eb-8a4e-ecec666ace87.png">


## 비동기식 호출

Review가 작성된 후에 Book 시스템으로 이를 알려주는 행위는 동기식이 아니라 비 동기식으로 처리한다.

- 이를 위하여 Review 이력에 기록을 남긴 후에 곧바로 도메인 이벤트를 카프카로 송출한다(Publish)

```
package movie;

@Entity
@Table(name="Review_table")
public class Review {

 ...
 
    @PostUpdate
    public void onPostUpdate(){
        WrittenReview writtenReview = new WrittenReview();
        BeanUtils.copyProperties(this, writtenReview);
        writtenReview.setStatus("Updated Review");
        writtenReview.publishAfterCommit();
    }
}
```

- Book 서비스에서는 WrittenReview 이벤트에 대해서 이를 수신하여 자신의 정책을 처리하도록 PolicyHandler 를 구현한다:

```
package movie;

...

@Service
public class PolicyHandler{

    @StreamListener(KafkaProcessor.INPUT)
    public void whenWrittenReviw(@Payload WrittenReview writtenReview) {
        if (writtenReview.isMe()) {
            // view 객체 생성
            System.out.println("======================================");
            System.out.println("**** listener  : " + writtenReview.toJson());
            System.out.println("======================================");
            bookRepository.findById(writtenReview.getBookingId()).ifPresent((book)->{
                book.setStatus("ReviewComplete");
                bookRepository.save(book);
            });
            
        }
    }  

}

```
- Book 시스템은 Review 서비스와 완전히 분리되어있으며, 이벤트 수신에 따라 처리되기 때문에, Book에서 2를 예매 후 인해 잠시 내려간 상태라도 Reviw를 작성하는데 문제가 없다

- Book 서비스에 예매 이벤트 발생 후 잠시 내려놓음 (ctrl+c)

1. 예매 처리 후 서비스 내려놓음
<img width="1014" alt="스크린샷 2021-02-23 오후 10 44 46" src="https://user-images.githubusercontent.com/28583602/108852274-bd33f380-7628-11eb-8eb9-70391b54f9c2.png">


```
cmd + C
```

2. Review 상태 확인
<img width="876" alt="스크린샷 2021-02-23 오후 10 46 52" src="https://user-images.githubusercontent.com/28583602/108852497-07b57000-7629-11eb-8826-18d7c8554597.png">

3. Book 서비스 기동
```
cd ../book
mvn spring-boot:run
```

4. 주문상태 확인
<img width="886" alt="스크린샷 2021-02-23 오후 10 52 17" src="https://user-images.githubusercontent.com/28583602/108853203-c96c8080-7629-11eb-89f8-b91273e2c39c.png">


## Gateway

- Gateway의 application.yaml에 모든 서비스들이 8088 포트를 사용할 수 있도록 한다.


```
# gateway.application.yaml
spring:
  profiles: default
  cloud:
    gateway:
      routes:
        - id: book
          uri: http://localhost:8081
          predicates:
            - Path=/books/** 
        - id: payment
          uri: http://localhost:8082
          predicates:
            - Path=/payments/** 
        - id: mypage
          uri: http://localhost:8083
          predicates:
            - Path= /mypages/**
        - id: ticket
          uri: http://localhost:8084
          predicates:
            - Path=/tickets/** 
        - id: review
          uri: http://localhost:8085
          predicates:
            - Path=/reviews/**             
      globalcors:
        corsConfigurations:
          '[/**]':
            allowedOrigins:
              - "*"
            allowedMethods:
              - "*"
            allowedHeaders:
              - "*"
            allowCredentials: true

```

- 8088 포트를 사용하여 API를 발생시킨다.

```
# book 서비스의 예매처리
http POST http://localhost:8088/books qty=2 movieName="soul" seat="1A,2B" totalPrice=10000

# ticket 서비스의 출력처리
http PATCH http://localhost:8088/tickets/1 status="Printed"

# review 서비스의 작성 처리
http PATCH http://localhost:8088/reviews/1 score=10 contents="VeryGood"

```
<img width="1077" alt="스크린샷 2021-02-23 오후 10 54 24" src="https://user-images.githubusercontent.com/28583602/108853441-151f2a00-762a-11eb-98d9-6aedbe8c27ed.png">

## Mypage

- 고객은 예매 상태를 Mypage에서 확인할 수 있다.

- REST API 의 테스트

```
# book 서비스의 예매처리
http POST http://localhost:8088/books qty=2 movieName="soul" seat="1A,2B" totalPrice=10000

# ticket 서비스의 출력처리
http PATCH http://localhost:8088/tickets/1 status="Printed"

# review 서비스의 작성 처리
http PATCH http://localhost:8088/reviews/1 score=10 contents="VeryGood"

```

<img width="717" alt="스크린샷 2021-02-23 오후 10 58 21" src="https://user-images.githubusercontent.com/28583602/108853937-a2fb1500-762a-11eb-9acb-789fec550b93.png">

## Polyglot

```
# Book - pom.xml

		<dependency>
			<groupId>com.h2database</groupId>
			<artifactId>h2</artifactId>
			<scope>runtime</scope>
		</dependency>


# Review - pom.xml

		<dependency>
			<groupId>org.hsqldb</groupId>
			<artifactId>hsqldb</artifactId>
			<scope>runtime</scope>
		</dependency>

```



# 운영

## Pipeline

각 구현체들은 Amazon ECR(Elastic Container Registry)에 구성되었고, 사용한 CI/CD 플랫폼은 AWS Codebuild며, pipeline build script 는 각 프로젝트 폴더 이하에 buildspec.yml 에 포함되었다. 

```
# review/buildspec.yaml
version: 0.2

env:
  variables:
    _PROJECT_NAME: "review"
    _PROJECT_DIR: "review"

phases:
  install:
    runtime-versions:
      java: openjdk8
      docker: 18
    commands:
      - echo install kubectl
      # - curl -LO https://storage.googleapis.com/kubernetes-release/release/$(curl -s https://storage.googleapis.com/kubernetes-release/release/stable.txt)/bin/linux/amd64/kubectl
      # - chmod +x ./kubectl
      # - mv ./kubectl /usr/local/bin/kubectl
  pre_build:
    commands:
      - echo Logging in to Amazon ECR...
      - echo $_PROJECT_NAME
      - echo $AWS_ACCOUNT_ID
      - echo $AWS_DEFAULT_REGION
      - echo $CODEBUILD_RESOLVED_SOURCE_VERSION
      - echo start command
      - $(aws ecr get-login --no-include-email --region $AWS_DEFAULT_REGION)
  build:
    commands:
      - echo Build started on `date`
      - echo Building the Docker image...
      - cd $_PROJECT_DIR
      - mvn package -Dmaven.test.skip=true
      - docker build -t $AWS_ACCOUNT_ID.dkr.ecr.$AWS_DEFAULT_REGION.amazonaws.com/skuser17-$_PROJECT_NAME:$CODEBUILD_RESOLVED_SOURCE_VERSION  .
  post_build:
    commands:
      - echo Pushing the Docker image...
      - docker push $AWS_ACCOUNT_ID.dkr.ecr.$AWS_DEFAULT_REGION.amazonaws.com/skuser17-$_PROJECT_NAME:$CODEBUILD_RESOLVED_SOURCE_VERSION
      - echo connect kubectl
      - kubectl config set-cluster k8s --server="$KUBE_URL" --insecure-skip-tls-verify=true
      - kubectl config set-credentials admin --token="$KUBE_TOKEN"
      - kubectl config set-context default --cluster=k8s --user=admin
      - kubectl config use-context default
      - |
        cat <<EOF | kubectl apply -f -
        apiVersion: v1
        kind: Service
        metadata:
          name: $_PROJECT_NAME
          namespace: movie
          labels:
            app: $_PROJECT_NAME
        spec:
          ports:
            - port: 8080
              targetPort: 8080
          selector:
            app: $_PROJECT_NAME
        EOF
      - |
        cat  <<EOF | kubectl apply -f -
        apiVersion: apps/v1
        kind: Deployment
        metadata:
          name: $_PROJECT_NAME
          namespace: movie
          labels:
            app: $_PROJECT_NAME
        spec:
          replicas: 1
          selector:
            matchLabels:
              app: $_PROJECT_NAME
          template:
            metadata:
              labels:
                app: $_PROJECT_NAME
            spec:
              containers:
                - name: $_PROJECT_NAME
                  image: $AWS_ACCOUNT_ID.dkr.ecr.$AWS_DEFAULT_REGION.amazonaws.com/skuser17-$_PROJECT_NAME:$CODEBUILD_RESOLVED_SOURCE_VERSION
                  ports:
                    - containerPort: 8080
        EOF
cache:
  paths:
    - "/root/.m2/**/*"

```

- Pipeline

<img width="979" alt="스크린샷 2021-02-24 오후 5 38 55" src="https://user-images.githubusercontent.com/28583602/108972947-2d915200-76c7-11eb-9ebf-1d3171473071.png">

## Zero-downtime deploy(Readiness Probe)

- seige 로 배포작업 직전에 워크로드를 모니터링 함.
```
siege -c50 -t120S -r10 --content-type "application/json" 'http://review:8080/reviews POST {"score":"3"}'

```

새버전으로 이미지 배포

```
kubectl set image deployment.apps/review review=496278789073.dkr.ecr.ap-northeast-2.amazonaws.com/skccuser17-review:8140c40acfe25a86482587b4449ee01cafdf17cd -n movie
```


- seige 의 화면으로 넘어가서 Availability 가 100% 미만으로 떨어졌는지 확인
![image](https://user-images.githubusercontent.com/28583602/108991383-60dddc00-76db-11eb-8254-9c1ee082d32c.png)

- review / buildspec.yaml 파일에 Readiness Probe 추가



```
          readinessProbe:
            httpGet:
              path: '/actuator/health'
              port: 8080
            initialDelaySeconds: 10
            timeoutSeconds: 2
            periodSeconds: 5
            failureThreshold: 10

```

- seige 로 배포작업 직전에 워크로드를 모니터링 함.
```
siege -c50 -t120S -r10 --content-type "application/json" 'http://review:8080/reviews POST {"score":"3"}'

```

새버전으로 이미지 배포

```
kubectl set image deployment.apps/review review=496278789073.dkr.ecr.ap-northeast-2.amazonaws.com/skccuser17-review:8140c40acfe25a86482587b4449ee01cafdf17cd -n movie
```
- seige 의 화면으로 넘어가서 Availability 가 100% 미만으로 떨어졌는지 확인

<img width="456" alt="스크린샷 2021-02-24 오후 8 05 12" src="https://user-images.githubusercontent.com/28583602/108991559-9c78a600-76db-11eb-83eb-a31113f43618.png">


## Self-healing(Liveness Probe)

- review/buildspec.yaml 파일에 Liveness Probe 추가

```
  livenessProbe:
    httpGet:
      path: /abc
      port: 8080
    initialDelaySeconds: 120
    timeoutSeconds: 2
    periodSeconds: 5
    failureThreshold: 5

```
<img width="707" alt="스크린샷 2021-02-24 오후 7 26 54" src="https://user-images.githubusercontent.com/28583602/108987108-42c1ad00-76d6-11eb-900b-7ffd2b87df59.png">

## Config Map

- review / deployment.yml에 env 추가


```
# deployment.yaml

          env:
            - name: WEATHER
              valueFrom:
                configMapKeyRef:
                  name: moviecm
                  key: text1

```

- Reviw 생성과 동시에 환경변수로 설정한 WEATHER이 들어가도록 코드를 변경

```
@Id
@GeneratedValue(strategy=GenerationType.AUTO)

...

    private String weather = System.getenv("WEATHER");;

```
- configmap.yaml 작성

```
apiVersion: v1
kind: ConfigMap
metadata:
  name: moviecm
  namespace: movie
data:
  text1: Sunny

```

- review pod에 들어가서 환경변수 확인

<img width="1110" alt="스크린샷 2021-02-24 오후 7 33 10" src="https://user-images.githubusercontent.com/28583602/108987875-23774f80-76d7-11eb-9ff9-b3dd520c4f13.png">

- Review 생성과 동시에 weather 환경변수 적용 

<img width="1058" alt="스크린샷 2021-02-24 오후 7 34 04" src="https://user-images.githubusercontent.com/28583602/108987976-430e7800-76d7-11eb-8fb6-dba436014bf0.png">




## Circuit Breaker

- 서킷 브레이킹 프레임워크의 선택: Spring FeignClient + Hystrix 옵션을 사용하여 구현함

- Hystrix 를 설정: 요청처리 쓰레드에서 처리시간이 610 밀리가 넘어서기 시작하여 어느정도 유지되면 CB 회로가 닫히도록 (요청을 빠르게 실패처리, 차단) 설정

```
# pom.yml in Ticket service

	<dependency>
		<groupId>org.springframework.cloud</groupId>
		<artifactId>spring-cloud-starter-netflix-hystrix</artifactId>
		<version>2.1.2.RELEASE</version>
	</dependency>
	<dependency>
		<groupId>org.springframework.cloud</groupId>
		<artifactId>spring-cloud-starter-netflix-hystrix-dashboard</artifactId>
		<version>2.1.2.RELEASE</version>
	</dependency>

# application.yml in Ticket service
    
hystrix:
  command:
    # 전역설정
    default:
      execution.isolation.thread.timeoutInMilliseconds: 610
feign:
  hystrix:
    enabled: true 

```

- 임의 부하 처리 - 400 밀리에서 증감 220 밀리 정도 왔다갔다 하게

```
# (review) Review.java (Entity)

    @PostPersist
    public void onPostPersist(){
        WaitedReview waitedReview = new WaitedReview();
        BeanUtils.copyProperties(this, waitedReview);
        waitedReview.publishAfterCommit();
        
        try {
            Thread.currentThread().sleep((long) (400 + Math.random() * 220));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }
```

- 부하테스터 siege 툴을 통한 서킷 브레이커 동작 확인:

* 동시사용자 50명
* 60초 동안 실시

```
# siege -c50 -t60S -r10 -v --content-type "application/json" 'http://ticket:8080/tickets POST {"bookingId":"3"}'

** SIEGE 4.0.4
** Preparing 50 concurrent users for battle.
The server is now under siege...


:
:
HTTP/1.1 201     5.32 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.22 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.23 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.19 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.07 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.11 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.16 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.12 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.24 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     5.26 secs:     249 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.21 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.28 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.26 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.23 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.24 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     5.41 secs:     249 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     5.46 secs:     249 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     5.57 secs:     249 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.46 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.28 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.25 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.15 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.20 secs:     254 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    30.38 secs:     820 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    30.39 secs:     820 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    30.39 secs:     820 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    30.37 secs:     820 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    30.37 secs:     820 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    30.38 secs:     820 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    30.38 secs:     820 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    30.39 secs:     820 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    30.40 secs:     820 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    30.38 secs:     820 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    30.38 secs:     820 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    30.40 secs:     820 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    30.38 secs:     820 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    30.42 secs:     820 bytes ==> POST http://ticket:8080/tickets


:
:

Lifting the server siege...
Transactions:		          88 hits
Availability:		       56.41 %
Elapsed time:		       59.96 secs
Data transferred:	        0.06 MB
Response time:		       26.34 secs
Transaction rate:	        1.47 trans/sec
Throughput:		        0.00 MB/sec
Concurrency:		       38.66
Successful transactions:          88
Failed transactions:	          68
Longest transaction:	       30.43
Shortest transaction:	        1.85

```

- 운영시스템은 죽지 않고 지속적으로 CB 에 의하여 적절히 회로가 열림과 닫힘이 벌어지면서 자원을 보호하고 있음을 보여줌.

- Retry 의 설정 (istio)
- Availability 가 높아진 것을 확인 (siege)

## Autoscale (HPA)

앞서 CB 는 시스템을 안정되게 운영할 수 있게 해줬지만 사용자의 요청을 100% 받아들여주지 못했기 때문에 이에 대한 보완책으로 자동화된 확장 기능을 적용하고자 한다.

- 결제서비스에 대한 deplyment.yml 파일에 해당 내용을 추가한다.

```
  resources:
    requests:
      cpu: "450m"
    limits:
      cpu: "500m"
```

- Review 서비스에 대한 replica 를 동적으로 늘려주도록 HPA 를 설정한다. 설정은 CPU 사용량이 70프로를 넘어서면 replica 를 10개까지 늘려준다:

```
 kubectl autoscale deploy review --min=1 --max=10 --cpu-percent=70 -n movie
```

- CB 에서 했던 방식대로 워크로드를 1분 동안 걸어준다.

```

# siege -c20 -t60S -r10 -v --content-type "application/json" 'http://ticket:8080/tickets POST {"bookingId":"3"}'

** SIEGE 4.0.4
** Preparing 20 concurrent users for battle.
The server is now under siege...
HTTP/1.1 201     0.47 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     1.00 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     1.51 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     2.03 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     2.65 secs:     249 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     3.22 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     3.67 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     4.25 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     4.67 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.19 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     5.75 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     6.21 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     6.82 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     7.37 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     7.80 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     8.27 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500     8.89 secs:     249 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201     9.47 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    10.07 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    10.64 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    10.65 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    10.67 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    10.66 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    10.62 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    10.53 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    10.44 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    10.46 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    10.32 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    10.46 secs:     256 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    10.38 secs:     256 bytes ==> POST http://ticket:8080/tickets
```

- 오토스케일이 어떻게 되고 있는지 모니터링을 걸어둔다:

```
kubectl get deploy payment -w
```

- 어느정도 시간이 흐른 후 (약 30초) 스케일 아웃이 벌어지는 것을 확인할 수 있다:

```
#pod 확인

NAME                           READY   STATUS    RESTARTS   AGE
pod/book-6c747ddd67-q9pnk      1/1     Running   0          19h
pod/gateway-6c6479b547-svmfc   1/1     Running   0          19h
pod/mypage-68c77cdd95-x67hz    1/1     Running   0          19h
pod/payment-7bd57d5b5b-c5n2g   1/1     Running   0          19h
pod/review-74766df9b-24srn     1/1     Running   0          2m46s
pod/review-74766df9b-2bv4h     1/1     Running   0          3m2s
pod/review-74766df9b-4l88g     1/1     Running   0          2m31s
pod/review-74766df9b-4m27g     1/1     Running   0          2m46s
pod/review-74766df9b-8gccq     1/1     Running   0          2m46s
pod/review-74766df9b-c98g5     1/1     Running   0          4m32s
pod/review-74766df9b-hvhcq     1/1     Running   0          3m2s
pod/review-74766df9b-lqq8n     1/1     Running   0          2m46s
pod/review-74766df9b-qb2l7     1/1     Running   0          3m2s
pod/seige-8657d6b95c-qct6d     1/1     Running   0          19h
pod/ticket-69f959dd64-x7htj    1/1     Running   0          9m1s

#deploy 확인

NAME     READY   UP-TO-DATE   AVAILABLE   AGE
review   1/1     1            1           25s
review   1/4     1            1           90s
review   1/4     1            1           90s
review   1/4     1            1           90s
review   1/4     4            1           90s
review   2/4     4            2           91s
review   3/4     4            3           92s
review   4/4     4            4           93s
review   4/8     4            4           106s
review   4/8     4            4           106s
review   4/8     4            4           106s
review   4/8     8            4           106s
review   5/8     8            5           108s
review   6/8     8            6           108s
review   7/8     8            7           108s
review   8/8     8            8           108s
review   8/9     8            8           2m1s
review   8/9     8            8           2m1s
review   8/9     8            8           2m1s
review   8/9     9            8           2m1s
review   9/9     9            9           2m3s
```

- siege 의 로그를 보아도 전체적인 성공률이 높아진 것을 확인 할 수 있다.

```
HTTP/1.1 201    10.93 secs:     258 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    10.89 secs:     258 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    10.93 secs:     249 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    10.95 secs:     249 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    10.97 secs:     249 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    11.00 secs:     258 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    10.97 secs:     258 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    10.98 secs:     258 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    11.04 secs:     258 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    11.00 secs:     258 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    11.10 secs:     249 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    11.15 secs:     249 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    11.23 secs:     258 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    11.29 secs:     249 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    11.25 secs:     258 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    11.18 secs:     258 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    11.18 secs:     258 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    11.16 secs:     258 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    11.12 secs:     258 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 500    11.13 secs:     249 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    11.01 secs:     258 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    11.00 secs:     258 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    10.92 secs:     258 bytes ==> POST http://ticket:8080/tickets
HTTP/1.1 201    10.81 secs:     258 bytes ==> POST http://ticket:8080/tickets

Lifting the server siege...
Transactions:		          84 hits
Availability:		       76.36 %
Elapsed time:		       59.07 secs
Data transferred:	        0.03 MB
Response time:		       12.84 secs
Transaction rate:	        1.42 trans/sec
Throughput:		        0.00 MB/sec
Concurrency:		       18.26
Successful transactions:          84
Failed transactions:	          26
Longest transaction:	       11.29
Shortest transaction:	        0.49
```

