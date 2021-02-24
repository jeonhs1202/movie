package movie;

import movie.config.kafka.KafkaProcessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

@Service
public class PolicyHandler{

    @Autowired
    BookRepository bookRepository;

    @StreamListener(KafkaProcessor.INPUT)
    public void onStringEventListener(@Payload String eventString){

    }

    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverPaid_(@Payload Paid paid){

        if(paid.isMe()){

            System.out.println("======================================");
            System.out.println("**** listener  : " + paid.toJson());
            System.out.println("======================================");
            bookRepository.findById(paid.getBookingId()).ifPresent((book)->{
                book.setStatus("PaidComplete");
                bookRepository.save(book);
            });

        }
    }

    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverPrinted_(@Payload Printed printed){

        if(printed.isMe()){
            System.out.println("##### listener  : " + printed.toJson());
        }
        if(printed.isMe()){

            System.out.println("======================================");
            System.out.println("**** listener  : " + printed.toJson());
            System.out.println("======================================");
            bookRepository.findById(printed.getBookingId()).ifPresent((book)->{
                book.setStatus("PrintComplete");
                bookRepository.save(book);
            });

        }
    };

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
