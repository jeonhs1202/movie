package movie;

import org.springframework.data.repository.PagingAndSortingRepository;
import java.util.List;

public interface TicketRepository extends PagingAndSortingRepository<Ticket, Long>{
    List<Ticket> findByBookingId(Long bookingId);

}