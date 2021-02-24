
package movie.external;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.Date;

@FeignClient(name="review", url="http://localhost:8085")
public interface ReviewService {

    @RequestMapping(method= RequestMethod.POST, path="/reviews")
    public void create(@RequestBody Review review);

}