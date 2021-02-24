package movie;

import movie.config.kafka.KafkaProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Service
public class MypageViewHandler {


    @Autowired
    private MypageRepository mypageRepository;

    @StreamListener(KafkaProcessor.INPUT)
    public void whenBooked_then_CREATE_1 (@Payload Booked booked) {
        try {
            if (booked.isMe()) {
                // view 객체 생성
                    Mypage mypage = new Mypage();
                // view 객체에 이벤트의 Value 를 set 함
                    mypage.setBookingId(booked.getId());
                    mypage.setQty(booked.getQty());
                    mypage.setMovieName(booked.getMovieName());
                    mypage.setSeat(booked.getSeat());
                    mypage.setPrice(booked.getTotalPrice());
                    mypage.setStatus("Booked");

                    // view 레파지 토리에 save
                    mypageRepository.save(mypage);
                }
        }catch (Exception e){
            e.printStackTrace();
        }
    }


    @StreamListener(KafkaProcessor.INPUT)
    public void whenCanceled_then_UPDATE_1(@Payload Canceled canceled) {
        try {
            if (canceled.isMe()) {
                // view 객체 조회
                List<Mypage> mypageList = mypageRepository.findByBookingId(canceled.getId());
                for(Mypage mypage : mypageList) {
                    mypage.setStatus("Canceled");

                    // view 레파지 토리에 save
                    mypageRepository.save(mypage);
                }
                
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }
    @StreamListener(KafkaProcessor.INPUT)
    public void whenPrinted_then_UPDATE_2(@Payload Printed printed) {
        try {
            if (printed.isMe()) {
                // view 객체 조회
                List<Mypage> mypageList = mypageRepository.findByBookingId(printed.getBookingId());
                for(Mypage mypage : mypageList) {
                    mypage.setStatus(printed.getStatus());

                    // view 레파지 토리에 save
                    mypageRepository.save(mypage);
                }
                
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    @StreamListener(KafkaProcessor.INPUT)
    public void whenWrittenReviw (@Payload WrittenReview writtenReview) {
        try {
            if (writtenReview.isMe()) {
                // view 객체 생성
                List<Mypage> mypageList = mypageRepository.findByBookingId(writtenReview.getBookingId());
                // view 객체에 이벤트의 Value 를 set 함
                for(Mypage mypage : mypageList) {
                    mypage.setScore(writtenReview.getScore());
                    mypage.setContents(writtenReview.getContents());
                    mypage.setStatus(writtenReview.getStatus());

                    // view 레파지 토리에 save
                    mypageRepository.save(mypage);
                }
                
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }    
}