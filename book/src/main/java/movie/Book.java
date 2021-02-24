package movie;

import javax.persistence.*;

import org.hibernate.annotations.SourceType;
import org.springframework.beans.BeanUtils;
import java.util.List;

@Entity
@Table(name="Book_table")
public class Book {

    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    private Long id;
    private Integer qty;
    private String seat;
    private String movieName;
    private String status="Registered";
    private Integer totalPrice;

    @PostPersist
    public void onPostPersist(){
        Booked booked = new Booked();
        BeanUtils.copyProperties(this, booked);
        booked.publishAfterCommit();

        //Following code causes dependency to external APIs
        // it is NOT A GOOD PRACTICE. instead, Event-Policy mapping is recommended.

        movie.external.Payment payment = new movie.external.Payment();
        
        System.out.println("*********************");
        System.out.println("결제 이벤트 발생");
        System.out.println("*********************");

        // mappings goes here
        payment.setBookingId(booked.getId());
        payment.setStatus("Paid");
        payment.setTotalPrice(booked.getTotalPrice());
        BookApplication.applicationContext.getBean(movie.external.PaymentService.class)
            .pay(payment);

    }

    @PreRemove
    public void onPreRemove(){

        if("PaidComplete".equals(status)){
            
            Canceled canceled = new Canceled();
            BeanUtils.copyProperties(this, canceled);
            canceled.publishAfterCommit();
            System.out.println("*********************");
            System.out.println("취소 이벤트 발생");
            System.out.println("*********************");
            //Following code causes dependency to external APIs
            // it is NOT A GOOD PRACTICE. instead, Event-Policy mapping is recommended.

            movie.external.Payment payment = new movie.external.Payment();
            payment.setBookingId(canceled.getId());
            payment.setStatus("Canceled");
            BookApplication.applicationContext.getBean(movie.external.PaymentService.class)
                .pay(payment);


        }
        

    }


    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
    public Integer getQty() {
        return qty;
    }

    public void setQty(Integer qty) {
        this.qty = qty;
    }
    public String getSeat() {
        return seat;
    }

    public void setSeat(String seat) {
        this.seat = seat;
    }
    public String getMovieName() {
        return movieName;
    }

    public void setMovieName(String movieName) {
        this.movieName = movieName;
    }
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
    public Integer getTotalPrice() {
        return totalPrice;
    }

    public void setTotalPrice(Integer totalPrice) {
        this.totalPrice = totalPrice;
    }




}
