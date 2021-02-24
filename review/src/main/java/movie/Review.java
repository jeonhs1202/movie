package movie;

import javax.persistence.*;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

@Entity
@Table(name="Review_table")
public class Review {

    @Value("${WEATHER}")
    private String weathertest;

    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    private Long id;
    private Long bookingId;
    private Integer score;
    private String contents;
    private String status;
    private String weather =weathertest;

    @PostPersist
    public void onPostPersist(){
        WaitedReview waitedReview = new WaitedReview();
        BeanUtils.copyProperties(this, waitedReview);
        waitedReview.publishAfterCommit();


    }

    @PostUpdate
    public void onPostUpdate(){
        WrittenReview writtenReview = new WrittenReview();
        BeanUtils.copyProperties(this, writtenReview);
        writtenReview.setStatus("Updated Review");
        writtenReview.publishAfterCommit();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
    public Long getBookingId() {
        return bookingId;
    }

    public void setBookingId(Long bookingId) {
        this.bookingId = bookingId;
    }
    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }
    public String getContents() {
        return contents;
    }

    public void setContents(String contents) {
        this.contents = contents;
    }
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }




}
